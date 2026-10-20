package com.ribau.baixador

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaCatalogTest {
    @Test fun canonicalizesSharedInstagramLinkWithoutTouchingOtherSites() {
        assertEquals("https://www.instagram.com/p/Desck_JCArL/", InstagramUrls.canonical("https://www.instagram.com/p/Desck_JCArL/?vrfl=MWFlZDc0aHM5Mzh1ZQ=="))
        assertNull(InstagramUrls.shortcode("https://instagram.com.evil.test/p/ABC/"))
        assertEquals("64", InstagramUrls.mediaId("BA"))
        assertEquals("https://youtube.com/watch?v=abc", InstagramUrls.canonical("https://youtube.com/watch?v=abc"))
    }
    @Test fun rejectsCredentialLeaksAndUntrustedMediaHosts() {
        assertFalse(InstagramUrls.isCdn("https://cdninstagram.com.evil.test/a.jpg"))
        assertFalse(InstagramUrls.isCdn("https://user:password@scontent.cdninstagram.com/a.jpg"))
        assertFalse(InstagramUrls.isCdn("http://scontent.cdninstagram.com/a.jpg"))
        assertFalse(InstagramUrls.isInstagram("https://instagram.com.evil.test/"))
    }
    @Test fun mixedCarouselKeepsPhotoVideoPhotoOrderAndDeduplicatesQuality() {
        val json = JSONObject("""{"items":[{"user":{"username":"example"},"carousel_media":[
          {"pk":"p1","media_type":1,"image_versions2":{"candidates":[{"url":"https://s.cdninstagram.com/1.jpg","width":1080,"height":1350,"file_size":300000}]}},
          {"pk":"v2","media_type":2,"has_audio":true,"video_duration":10,"video_versions":[
            {"url":"https://s.cdninstagram.com/2.mp4","width":720,"height":1280},
            {"url":"https://s.cdninstagram.com/2b.mp4","width":720,"height":1280}],"image_versions2":{"candidates":[{"url":"https://s.cdninstagram.com/2.jpg","width":720,"height":1280}]}},
          {"pk":"p3","media_type":1,"image_versions2":{"candidates":[{"url":"https://s.cdninstagram.com/3.jpg","width":1080,"height":1080}]}}
        ]}]}""")
        val result = CatalogParser.instagram(json, "https://www.instagram.com/p/test/")
        assertEquals(listOf("p1", "v2", "p3"), result.items.map { it.id })
        assertEquals(listOf("Foto", "Vídeo", "Foto"), result.items.map { it.kind })
        assertEquals(1, result.items[0].options.size)
        assertEquals(300000, result.items[0].options.single().bytes)
        assertEquals(4, result.items[1].options.size)
        assertEquals(240000, result.items[1].options.first { it.bitrate == 192 && it.audio }.bytes)
        assertEquals(0, result.items[2].options.single().bytes)
    }
    @Test fun supportsGraphqlCarouselIncludingPhotos() {
        val json = JSONObject("""{"data":{"xdt_shortcode_media":{"owner":{"username":"example"},"edge_sidecar_to_children":{"edges":[{"node":{"id":"photo","is_video":false,"display_url":"https://s.fbcdn.net/photo.jpg","dimensions":{"width":1000,"height":1200}}}]}}}}""")
        val item = CatalogParser.instagram(json, "url").items.single()
        assertEquals("Foto", item.kind)
        assertTrue(item.options.single().label.contains("1000×1200"))
    }
    @Test fun videoSizeIncludesSeparateAudioAndUsesExactSelector() {
        val json = JSONObject("""{"id":"v","title":"test","duration":10,"formats":[
          {"format_id":"sb","vcodec":"images","ext":"mhtml","height":90},
          {"format_id":"137","vcodec":"avc1","acodec":"none","ext":"mp4","height":1080,"filesize":1000000},
          {"format_id":"140","vcodec":"none","acodec":"mp4a","ext":"m4a","abr":128,"filesize":160000}
        ]}""")
        val option = CatalogParser.youtube(json, "url").items.single().options.first()
        assertEquals("137+140", option.selector)
        assertEquals(1160000, option.bytes)
        assertTrue(option.estimated)
    }
    @Test fun supportsLoggedOutGraphqlAndDoesNotInventAudioForSilentVideos() {
        val json = JSONObject("""{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{"user":{"username":"example"},"media_type":2,"has_audio":false,"video_versions":[{"url":"https://s.cdninstagram.com/video.mp4","width":1080,"height":1920}]}}}}""")
        val item = CatalogParser.instagram(json, "url").items.single()
        assertEquals("Vídeo", item.kind)
        assertEquals(1, item.options.size)
        assertFalse(item.options.single().audio)
    }
    @Test(expected = IllegalArgumentException::class)
    fun gatedGraphqlDoesNotReturnFakeMedia() {
        CatalogParser.instagram(JSONObject("""{"data":{"xig_polaris_media":{"if_not_gated_logged_out":null}}}"""), "url")
    }
    @Test fun missingAudioSizeDoesNotProduceMisleadingVideoTotal() {
        val json = JSONObject("""{"formats":[{"format_id":"v","vcodec":"avc1","acodec":"none","height":720,"filesize":1000},
          {"format_id":"a","vcodec":"none","acodec":"aac"}]}""")
        assertEquals(0, CatalogParser.youtube(json, "url").items.single().options.first().bytes)
        assertEquals("tamanho não informado", MediaOption.sizeLabel(0))
    }
    @Test(expected = IllegalArgumentException::class)
    fun unavailableCarouselItemIsNotSilentlySkipped() {
        CatalogParser.instagram(JSONObject("""{"carousel_media":[{"media_type":2,"image_versions2":{"candidates":[{"url":"https://s.cdninstagram.com/thumb.jpg"}]}}]}"""), "url")
    }
}
