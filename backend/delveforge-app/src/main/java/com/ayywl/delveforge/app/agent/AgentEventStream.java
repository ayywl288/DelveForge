package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentStore;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 历史与后续事件始终来自同一持久化分页查询；不存在内存订阅切换窗口。 */
public final class AgentEventStream implements AutoCloseable {
    private final AgentStore store;
    private final Semaphore capacity = new Semaphore(64);
    private final Set<SseEmitter> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public AgentEventStream(AgentStore store) {
        this.store = store;
    }

    public SseEmitter open(String runId, long after) {
        if (closed) {
            throw new AgentException("STREAM_CAPACITY", "Application is stopping");
        }
        store.get(runId);
        if (after < 0) {
            throw new AgentException("INVALID_REQUEST", "Event cursor must be nonnegative");
        }
        if (!capacity.tryAcquire()) {
            throw new AgentException("STREAM_CAPACITY", "Too many event subscriptions");
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscriptions.add(emitter);
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable stop = () -> stopped.set(true);
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(failure -> stop.run());
        Thread.ofVirtual().name("agent-events-" + runId).start(() -> {
            long cursor = after;
            long heartbeat = 0;
            try {
                while (!closed && !stopped.get()) {
                    var events = store.events(runId, cursor, 100);
                    for (var event : events) {
                        if (stopped.get()) {
                            break;
                        }
                        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("activity").data(event));
                        cursor = event.sequence();
                    }
                    if (System.nanoTime() - heartbeat > 10_000_000_000L) {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                        heartbeat = System.nanoTime();
                    }
                    if (events.size() < 100) {
                        Thread.sleep(400);
                    }
                }
            } catch (IOException | RuntimeException failure) {
                emitter.completeWithError(failure);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } finally {
                stopped.set(true);
                subscriptions.remove(emitter);
                emitter.complete();
                capacity.release();
            }
        });
        return emitter;
    }

    /** 先于 Web server 优雅停机结束长连接，避免等待订阅本身的三十分钟超时。 */
    @Override
    public void close() {
        closed = true;
        subscriptions.forEach(SseEmitter::complete);
    }
}
