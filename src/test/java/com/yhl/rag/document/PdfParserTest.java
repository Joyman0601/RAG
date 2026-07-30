package com.yhl.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class PdfParserTest {

    @Test
    void parse_extractsTextAndEmbeddedImage() throws Exception {
        // Standard14 字体（Helvetica）只能编码 ASCII，故 PDF 内文用英文；解析行为与中文一致。
        byte[] pdf = buildPdfWithTextAndImage("Annual leave application flow");

        PdfParser.PdfParseResult result = new PdfParser().parse(pdf);

        assertThat(result.text()).contains("Annual leave application flow");
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().get(0).mimeType()).isEqualTo("image/png");
        assertThat(result.images().get(0).bytes()).isNotEmpty();
    }

    @Test
    void parse_textOnlyPdf_returnsNoImages() throws Exception {
        byte[] pdf = buildTextOnlyPdf("plain text only");

        PdfParser.PdfParseResult result = new PdfParser().parse(pdf);

        assertThat(result.text()).contains("plain text only");
        assertThat(result.images()).isEmpty();
    }

    private static byte[] buildTextOnlyPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildPdfWithTextAndImage(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage bufferedImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    bufferedImage.setRGB(x, y, Color.BLUE.getRGB());
                }
            }
            PDImageXObject image = LosslessFactory.createFromImage(document, bufferedImage);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
                content.drawImage(image, 50, 500, 100, 100);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
