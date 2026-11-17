package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type reply struct {
	Status int
	Body   json.RawMessage
}
type query struct {
	ID, Token, Kind, Ref string
	Offset, Limit        int
	done                 chan reply
}
type relay struct {
	sync.Mutex
	requests chan *query
	waiting  map[string]*query
}

func newRelay() *relay {
	return &relay{requests: make(chan *query, 32), waiting: make(map[string]*query)}
}
func readRange(req *http.Request, name string, min, max int, value *int) bool {
	s := req.URL.Query().Get(name)
	if s == "" {
		return true
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < min || n > max {
		return false
	}
	*value = n
	return true
}
func (r *relay) guest(w http.ResponseWriter, req *http.Request) {
	kind := strings.TrimPrefix(req.URL.Path, "/lookup/")
	if req.URL.Path == "/preview" {
		kind = "preview"
		if req.Method != http.MethodPost {
			w.WriteHeader(405)
			return
		}
	} else {
		if kind != "history" && kind != "output" {
			http.NotFound(w, req)
			return
		}
		if req.Method != http.MethodGet {
			w.WriteHeader(405)
			return
		}
	}
	token, ok := strings.CutPrefix(req.Header.Get("Authorization"), "Bearer ")
	if !ok || token == "" || len(token) > 256 {
		http.Error(w, "forbidden", 403)
		return
	}
	q := &query{Token: token, Kind: kind, Ref: req.URL.Query().Get("ref"), Limit: 4096, done: make(chan reply, 1)}
	for k := range req.URL.Query() {
		if k != "ref" && k != "offset" && k != "limit" {
			http.Error(w, "invalid query", 400)
			return
		}
	}
	if !readRange(req, "offset", 0, 1<<30, &q.Offset) || !readRange(req, "limit", 1, 8192, &q.Limit) || len(q.Ref) > 200 {
		http.Error(w, "invalid range", 400)
		return
	}
	result := r.ask(req.Context(), q)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(result.Status)
	w.Write(result.Body)
}

func (r *relay) ask(ctx context.Context, q *query) reply {
	var nonce [16]byte
	if _, e := rand.Read(nonce[:]); e != nil {
		return reply{Status: 503}
	}
	q.ID = hex.EncodeToString(nonce[:])
	r.Lock()
	r.waiting[q.ID] = q
	r.Unlock()
	defer func() { r.Lock(); delete(r.waiting, q.ID); r.Unlock() }()
	select {
	case r.requests <- q:
	default:
		return reply{Status: 503}
	}
	select {
	case result := <-q.done:
		return result
	case <-time.After(8 * time.Second):
		return reply{Status: 503}
	case <-ctx.Done():
		return reply{Status: 503}
	}
}
func (r *relay) control(w http.ResponseWriter, req *http.Request) {
	if req.Method == "GET" && req.URL.Path == "/relay/next" {
		for {
			select {
			case q := <-r.requests:
				r.Lock()
				live := r.waiting[q.ID] != nil
				r.Unlock()
				if live {
					json.NewEncoder(w).Encode(q)
					return
				}
			case <-time.After(2 * time.Second):
				w.WriteHeader(204)
				return
			case <-req.Context().Done():
				return
			}
		}
	}
	if req.Method == "POST" && strings.HasPrefix(req.URL.Path, "/relay/result/") {
		id := strings.TrimPrefix(req.URL.Path, "/relay/result/")
		var result reply
		if json.NewDecoder(http.MaxBytesReader(w, req.Body, 65536)).Decode(&result) != nil || result.Status < 200 || result.Status > 599 || result.Body == nil {
			http.Error(w, "invalid reply", 400)
			return
		}
		r.Lock()
		q := r.waiting[id]
		r.Unlock()
		if q == nil {
			w.WriteHeader(410)
			return
		}
		select {
		case q.done <- result:
			w.WriteHeader(204)
		default:
			w.WriteHeader(409)
		}
		return
	}
	http.NotFound(w, req)
}
