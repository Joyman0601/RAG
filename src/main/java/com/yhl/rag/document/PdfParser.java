package com.yhl.rag.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PDF 解析（PDFBox，手写不引检索框架）：抽取正文文本 + 内嵌图片对象。
 * 文本走原有文本分块链路；图片各自作为 IMAGE chunk 走 VL 图像 embedding。
 * 单个图片解码失败只跳过该图、不中断整篇解析。
 */
@Component
public class PdfParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

    public PdfParseResult parse(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            List<ExtractedImage> images = extractImages(document);
            log.info("pdf_parse pages={} textChars={} imageCount={}",
                    document.getNumberOfPages(), text == null ? 0 : text.length(), images.size());
            return new PdfParseResult(text == null ? "" : text, images);
        } catch (IOException exception) {
            throw new DocumentException("DOCUMENT_PDF_PARSE_FAILED", "PDF 解析失败：" + exception.getMessage(), exception);
        }
    }

    private List<ExtractedImage> extractImages(PDDocument document) {
        List<ExtractedImage> images = new ArrayList<>();
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : resources.getXObjectNames()) {
                try {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject imageXObject) {
                        byte[] png = toPng(imageXObject.getImage());
                        if (png.length > 0) {
                            images.add(new ExtractedImage(png, "image/png", pageNumber));
                        }
                    }
                } catch (IOException | RuntimeException exception) {
                    log.warn("pdf_image_skip page={} name={} reason={}", pageNumber, name.getName(), exception.getMessage());
                }
            }
        }
        return images;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    public record PdfParseResult(String text, List<ExtractedImage> images) {
    }

    public record ExtractedImage(byte[] bytes, String mimeType, int page) {
    }
}
