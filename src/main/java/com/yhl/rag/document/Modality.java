package com.yhl.rag.document;

/** chunk 模态：TEXT 走文本 embedding；IMAGE 走 VL 图像 embedding，二者进同一向量空间。 */
public enum Modality {
    TEXT,
    IMAGE
}
