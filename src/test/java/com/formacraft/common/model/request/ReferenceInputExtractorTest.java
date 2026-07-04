package com.formacraft.common.model.request;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceInputExtractorTest {

    @Test
    void extractsHttpImageUrl() {
        List<ReferenceInput> refs = ReferenceInputExtractor.extractFromText(
                "照着这个建 https://example.com/photo.jpg"
        );
        assertEquals(1, refs.size());
        assertEquals("image_url", refs.get(0).getType());
        assertTrue(refs.get(0).getContent().contains("photo.jpg"));
    }

    @Test
    void extractsWebUrl() {
        List<ReferenceInput> refs = ReferenceInputExtractor.extractFromText(
                "参考 https://zh.wikipedia.org/wiki/苏州博物馆"
        );
        assertEquals(1, refs.size());
        assertEquals("web_url", refs.get(0).getType());
    }

    @Test
    void extractsDataUri() {
        List<ReferenceInput> refs = ReferenceInputExtractor.extractFromText(
                "data:image/png;base64,abc123="
        );
        assertEquals(1, refs.size());
        assertEquals("image_base64", refs.get(0).getType());
    }

    @Test
    void deduplicatesUrls() {
        String url = "https://example.com/a.png";
        List<ReferenceInput> refs = ReferenceInputExtractor.extractFromText(
                url + " and again " + url
        );
        assertEquals(1, refs.size());
    }
}
