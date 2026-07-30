package com.yhl.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真 VL embedding 端点的多模态集成测试，provider 无关，靠环境变量驱动。默认禁用；设 EMB_IT_API_KEY 才运行：
 * <pre>
 *   # SiliconFlow（OpenAI 兼容，Qwen3-VL-Embedding-8B，4096 维）
 *   EMB_IT_API_KEY=sk-... EMB_IT_BASE_URL=https://api.siliconflow.cn \
 *     EMB_IT_MODEL=Qwen/Qwen3-VL-Embedding-8B EMB_IT_STYLE=openai \
 *     mvn test -Dtest=EmbeddingClientLiveIT
 *   # DashScope（原生多模态，qwen3-vl-embedding，2560 维）
 *   EMB_IT_API_KEY=sk-... \
 *     EMB_IT_BASE_URL=https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding \
 *     EMB_IT_MODEL=qwen3-vl-embedding EMB_IT_STYLE=dashscope-multimodal \
 *     mvn test -Dtest=EmbeddingClientLiveIT
 * </pre>
 * 验证文本与图像经同一 VL 模型进**同一向量空间**（同维），并以颜色匹配证明"文本 query 召回正确图片"。
 */
@EnabledIfEnvironmentVariable(named = "EMB_IT_API_KEY", matches = ".+")
class EmbeddingClientLiveIT {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClientLiveIT.class);

    private EmbeddingClient client() {
        LlmProperties props = new LlmProperties();
        props.setEmbeddingApiKey(System.getenv("EMB_IT_API_KEY"));
        props.setEmbeddingBaseUrl(System.getenv().getOrDefault("EMB_IT_BASE_URL", "https://api.siliconflow.cn"));
        props.setEmbeddingModel(System.getenv().getOrDefault("EMB_IT_MODEL", "Qwen/Qwen3-VL-Embedding-8B"));
        props.setEmbeddingStyle(System.getenv().getOrDefault("EMB_IT_STYLE", "openai"));
        props.setEmbeddingTimeout(60);
        return new EmbeddingClient(props, new ObjectMapper());
    }

    @Test
    void textAndImage_sameVectorSpace_andColorTextRecallsMatchingImage() {
        EmbeddingClient client = client();

        List<Double> redText = client.embed("一张纯红色的图片 a solid red image");
        List<Double> blueText = client.embed("一张纯蓝色的图片 a solid blue image");
        List<Double> redImage = client.embedImage(solidPng(Color.RED), "image/png");
        List<Double> blueImage = client.embedImage(solidPng(Color.BLUE), "image/png");

        // 真多模态核心：文本与图像同维（同一向量空间），cosine 可直接比较。
        assertThat(redImage).hasSize(redText.size());
        assertThat(blueImage).hasSize(blueText.size());
        log.info("emb_live_dim style={} text={} image={}",
                System.getenv().getOrDefault("EMB_IT_STYLE", "openai"), redText.size(), redImage.size());

        double redToRed = cosine(redText, redImage);
        double redToBlue = cosine(redText, blueImage);
        double blueToBlue = cosine(blueText, blueImage);
        double blueToRed = cosine(blueText, redImage);
        log.info("emb_live_cross_modal redText.redImg={} redText.blueImg={} blueText.blueImg={} blueText.redImg={}",
                redToRed, redToBlue, blueToBlue, blueToRed);

        // 跨模态召回证据：颜色匹配的「文本×图片」相似度高于不匹配的。
        assertThat(redToRed).isGreaterThan(redToBlue);
        assertThat(blueToBlue).isGreaterThan(blueToRed);
    }

    private static byte[] solidPng(Color color) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static double cosine(List<Double> a, List<Double> b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
