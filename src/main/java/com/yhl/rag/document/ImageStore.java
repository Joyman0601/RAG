package com.yhl.rag.document;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * IMAGE chunk 的图片字节存储（demo 内存实现）：put 返回 imageRef，chunk 记 imageRef，
 * 展示/回填时按 ref 取回字节与 mime。生产可换磁盘 / 对象存储而不动调用方。
 */
@Component
public class ImageStore {

    private final ConcurrentMap<String, StoredImage> images = new ConcurrentHashMap<>();

    public String put(byte[] bytes, String mimeType) {
        String ref = UUID.randomUUID().toString();
        images.put(ref, new StoredImage(bytes.clone(), mimeType));
        return ref;
    }

    public Optional<StoredImage> get(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(images.get(ref));
    }

    public void remove(String ref) {
        if (ref != null) {
            images.remove(ref);
        }
    }

    public int size() {
        return images.size();
    }

    public record StoredImage(byte[] bytes, String mimeType) {
    }
}
