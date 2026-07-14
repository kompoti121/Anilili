package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReanimeFlixcloudParserTest {
    @Test
    fun ranksSubtitleDefaultsByAudioMode() {
        val html = """
            subtitles:[
              {url:"https://vault.test/subtitles/v/v_eng_1.ass",language:"English (Signs & Songs)",format:"ass",default:false},
              {url:"https://vault.test/subtitles/v/v_tha_2.ass",language:"Thai (Track 18 (THA))",format:"ass",default:false},
              {url:"https://vault.test/subtitles/v/v_eng_3.ass",language:"English (Full Subtitles)",format:"ass",default:false},
              {url:"https://vault.test/subtitles/v/v_eng_4.ass",language:"English (Forced)",format:"ass",default:false}
            ]
        """.trimIndent()

        val subTracks = ReanimeFlixcloudParser.subtitles(html, "sub")
        val dubTracks = ReanimeFlixcloudParser.subtitles(html, "dub")

        assertEquals("English (Full Subtitles)", subTracks.first().label)
        assertEquals("English (Forced)", dubTracks.first().label)
        assertEquals("en", subTracks.first().language)
        assertEquals("th", subTracks.last().language)
    }

    @Test
    fun extractsOptionalChaptersAndDefaultAudio() {
        val html = """
            intro_chapter:{start:78,end:169,title:"Intro"},
            chapters:[{start:169,end:1358,title:"Part 1"},{start:1358,end:1450,title:"Credits"}],
            default_audio_track:1
        """.trimIndent()

        val skip = ReanimeFlixcloudParser.skip(html)

        assertEquals(78.0, skip?.introStart)
        assertEquals(169.0, skip?.introEnd)
        assertEquals(1358.0, skip?.outroStart)
        assertEquals(1450.0, skip?.outroEnd)
        assertEquals(1, ReanimeFlixcloudParser.defaultAudioTrack(html))
        assertNull(ReanimeFlixcloudParser.skip("chapters:[]"))
    }

    @Test
    fun parsesCurrentFlixcloudSubtitlePayload() {
        val html = """
            subtitles:[
              {url:"https://vault-95.rundowncdn.top/subtitles/video/video_eng_3.srt",language:"English (Track 3 (ENG))",format:"srt",default:true},
              {url:"https://vault-95.rundowncdn.top/subtitles/video/video_eng_4.srt",language:"English (Forced)",format:"srt",default:false}
            ],player_settings:{primary_color:"#23ade5"}
        """.trimIndent()

        val subtitles = ReanimeFlixcloudParser.subtitles(html, "sub")

        assertEquals(2, subtitles.size)
        assertEquals("English (Track 3 (ENG))", subtitles.first().label)
        assertEquals("en", subtitles.first().language)
    }
}
