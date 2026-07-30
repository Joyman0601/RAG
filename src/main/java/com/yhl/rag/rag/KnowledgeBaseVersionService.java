package com.yhl.rag.rag;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

@Component
public class KnowledgeBaseVersionService {

    private final AtomicLong version = new AtomicLong(1);

    public long currentVersion() {
        return version.get();
    }

    public long incrementAndGet() {
        return version.incrementAndGet();
    }
}
