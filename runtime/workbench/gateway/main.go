package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type listener struct{ Address, Inode string }

func find(port int) (listener, bool) {
	for _, table := range []struct{ path, addr string }{{"/proc/net/tcp", "127.0.0.1"}, {"/proc/net/tcp6", "::1"}} {
		f, err := os.Open(table.path)
		if err != nil {
			continue
		}
		scan := bufio.NewScanner(f)
		var found listener
		for scan.Scan() {
			fields := strings.Fields(scan.Text())
			if len(fields) < 10 || fields[3] != "0A" {
				continue
			}
			parts := strings.Split(fields[1], ":")
			if len(parts) != 2 {
				continue
			}
			n, _ := strconv.ParseInt(parts[1], 16, 32)
			if int(n) != port {
				continue
			}
			switch parts[0] {
			case "00000000", "0100007F", "00000000000000000000000000000000", "00000000000000000000000001000000":
				if found.Inode != "" {
					f.Close()
					return listener{}, false
				}
				found = listener{net.JoinHostPort(table.addr, strconv.Itoa(port)), fields[9]}
			}
		}
		f.Close()
		if found.Inode != "" {
			return found, true
		}
	}
	return listener{}, false
}

type route struct {
	Host, Project string
	Port          int
	Bind          listener
	Proxy         *httputil.ReverseProxy
	Transport     *http.Transport
	stale         bool
}
type registry struct {
	sync.RWMutex
	hosts map[string]*route
}

func (r *registry) expire(p *route) {
	r.Lock()
	fresh := !p.stale
	p.stale = true
	r.Unlock()
	if fresh {
		p.Transport.CloseIdleConnections()
	}
}
func (r *registry) live(p *route) bool {
	r.RLock()
	stale := p.stale
	r.RUnlock()
	if stale {
		return false
	}
	got, ok := find(p.Port)
	if !ok || got != p.Bind {
		r.expire(p)
		return false
	}
	return true
}
func (r *registry) register(w http.ResponseWriter, req *http.Request) {
	if req.Method != "POST" || req.URL.Path != "/routes" {
		http.NotFound(w, req)
		return
	}
	var cmd struct {
		Project           string
		Inode             string
		Port, WindowsPort int
	}
	if json.NewDecoder(http.MaxBytesReader(w, req.Body, 4096)).Decode(&cmd) != nil || cmd.Project == "" || cmd.Port < 1 || cmd.Port > 65535 || cmd.Port >= 8400 && cmd.Port <= 8402 || cmd.WindowsPort < 1 || cmd.WindowsPort > 65535 {
		http.Error(w, "invalid route", 400)
		return
	}
	id, ok := find(cmd.Port)
	if !ok || id.Inode != cmd.Inode {
		http.Error(w, "listener unavailable", 409)
		return
	}
	var bits [12]byte
	if _, err := rand.Read(bits[:]); err != nil {
		http.Error(w, "unavailable", 503)
		return
	}
	p := &route{Host: "p-" + hex.EncodeToString(bits[:]) + ".localhost", Project: cmd.Project, Port: cmd.Port, Bind: id}
	dst := &url.URL{Scheme: "http", Host: id.Address}
	p.Transport = &http.Transport{Proxy: nil, IdleConnTimeout: 45 * time.Second, ResponseHeaderTimeout: 15 * time.Second}
	p.Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		if !r.live(p) {
			return nil, errors.New("listener ended")
		}
		c, err := (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, network, addr)
		if err == nil && !r.live(p) {
			c.Close()
			return nil, errors.New("listener changed")
		}
		return c, err
	}
	p.Proxy = &httputil.ReverseProxy{Transport: p.Transport, Rewrite: func(pr *httputil.ProxyRequest) {
		pr.SetURL(dst)
		pr.Out.Host = pr.In.Host
		pr.SetXForwarded()
	}, ErrorHandler: func(w http.ResponseWriter, _ *http.Request, _ error) {
		if !r.live(p) {
			http.Error(w, "preview ended", 410)
		} else {
			http.Error(w, "preview unavailable", 502)
		}
	}}
	checkCtx, cancel := context.WithTimeout(req.Context(), 3*time.Second)
	defer cancel()
	check, err := http.NewRequestWithContext(checkCtx, http.MethodHead, dst.String(), nil)
	if err != nil {
		http.Error(w, "invalid HTTP target", 400)
		return
	}
	check.Host = p.Host
	first, err := p.Transport.RoundTrip(check)
	if err != nil {
		p.Transport.CloseIdleConnections()
		http.Error(w, "HTTP preview unavailable", 409)
		return
	}
	first.Body.Close()
	r.Lock()
	for _, other := range r.hosts {
		if !other.stale && other.Port == p.Port && other.Bind == id && other.Project != p.Project {
			r.Unlock()
			http.Error(w, "listener already registered", 409)
			return
		}
	}
	r.hosts[p.Host] = p
	r.Unlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"url": fmt.Sprintf("http://%s:%d", p.Host, cmd.WindowsPort)})
}
func (r *registry) preview(w http.ResponseWriter, req *http.Request) {
	host, _, err := net.SplitHostPort(req.Host)
	if err != nil {
		host = req.Host
	}
	r.RLock()
	p := r.hosts[strings.ToLower(host)]
	stale := p != nil && p.stale
	r.RUnlock()
	if p == nil {
		http.NotFound(w, req)
		return
	}
	if stale {
		http.Error(w, "preview ended", 410)
		return
	}
	p.Proxy.ServeHTTP(w, req)
}
func main() {
	cert, err := os.ReadFile("/cert/ca.crt")
	if err != nil {
		log.Fatal(err)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(cert) {
		log.Fatal("invalid control CA")
	}
	r := &registry{hosts: make(map[string]*route)}
	go func() {
		for range time.Tick(250 * time.Millisecond) {
			r.RLock()
			var registered []*route
			for _, p := range r.hosts {
				if !p.stale {
					registered = append(registered, p)
				}
			}
			r.RUnlock()
			for _, p := range registered {
				r.live(p)
			}
		}
	}()
	go func() {
		log.Fatal((&http.Server{Addr: ":8400", Handler: http.HandlerFunc(r.preview), ReadHeaderTimeout: 5 * time.Second}).ListenAndServe())
	}()
	relay := newRelay()
	go func() {
		log.Fatal((&http.Server{Addr: "127.0.0.1:8402", Handler: http.HandlerFunc(relay.guest), ReadHeaderTimeout: 5 * time.Second}).ListenAndServe())
	}()
	control := http.NewServeMux()
	control.HandleFunc("/routes", r.register)
	control.HandleFunc("/relay/", relay.control)
	s := &http.Server{Addr: ":8401", Handler: control, ReadHeaderTimeout: 5 * time.Second,
		TLSConfig: &tls.Config{MinVersion: tls.VersionTLS13, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: roots},
		ErrorLog:  log.New(io.Discard, "", 0)}
	log.Fatal(s.ListenAndServeTLS("/cert/server.crt", "/cert/server.key"))
}
