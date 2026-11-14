package ai.opencyclone.voice

import ai.opencyclone.voice.AndroidSpeechInputEngine.Candidate
import ai.opencyclone.voice.AndroidSpeechInputEngine.Companion.errorAction
import ai.opencyclone.voice.AndroidSpeechInputEngine.Companion.pickService
import ai.opencyclone.voice.AndroidSpeechInputEngine.ErrorAction
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSpeechInputEngineTest {
    private val own = "ai.opencyclone.standard"

    @Test fun silenceAndNoMatchMeanNothingWasHeard() {
        assertEquals(ErrorAction.HEARD_NOTHING, errorAction(SpeechRecognizer.ERROR_NO_MATCH, onDevice = true))
        assertEquals(ErrorAction.HEARD_NOTHING, errorAction(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, onDevice = false))
    }

    @Test fun permissionBusyClientAndAudioHaveTheirOwnSentences() {
        assertEquals(ErrorAction.NO_PERMISSION, errorAction(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, true))
        assertEquals(ErrorAction.COULD_NOT_LISTEN, errorAction(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, true))
        assertEquals(ErrorAction.COULD_NOT_LISTEN, errorAction(SpeechRecognizer.ERROR_CLIENT, false))
        assertEquals(ErrorAction.MIC_BUSY, errorAction(SpeechRecognizer.ERROR_AUDIO, true))
        assertEquals(ErrorAction.COULD_NOT_LISTEN, errorAction(999, true))
    }

    @Test fun aMissingLanguageOnDeviceAsksForTheDownloadAndFallsBack() {
        assertEquals(ErrorAction.LANGUAGE_MISSING, errorAction(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, onDevice = true))
        assertEquals(ErrorAction.LANGUAGE_MISSING, errorAction(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, onDevice = true))
        // A service that refuses the language: the other engine takes the turn.
        assertEquals(ErrorAction.UNAVAILABLE, errorAction(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, onDevice = false))
    }

    @Test fun serverAndNetworkErrorsHandTheTurnToTheOtherEngine() {
        listOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            .forEach { assertEquals(ErrorAction.UNAVAILABLE, errorAction(it, true)) }
    }

    @Test fun ourOwnStubIsNeverPickedAndSystemServicesComeFirst() {
        val picked = pickService(listOf(
            Candidate(own, "ai.opencyclone.assist.StubRecognitionService", system = false),
            Candidate("com.example.other", "com.example.Rec", system = false),
            Candidate("com.google.android.tts", "com.google.Tts", system = true),
        ), own)
        assertEquals(Candidate("com.google.android.tts", "com.google.Tts", true), picked)
    }

    @Test fun anotherAppIsUsedWhenThereIsNoSystemService() {
        val picked = pickService(listOf(
            Candidate(own, "ai.opencyclone.assist.StubRecognitionService", system = false),
            Candidate("com.example.other", "com.example.Rec", system = false),
        ), own)
        assertEquals(Candidate("com.example.other", "com.example.Rec", false), picked)
    }

    @Test fun onlyOurStubMeansNotAvailable() {
        assertNull(pickService(listOf(Candidate(own, "ai.opencyclone.assist.StubRecognitionService", false)), own))
        assertNull(pickService(emptyList(), own))
    }

    @Test fun decibelsMapOntoTheLevelScale() {
        assertEquals(0f, MicLevel.fromDecibels(-2f), 0f)
        assertEquals(0.3f, MicLevel.fromDecibels(10f), 1e-6f)
        assertEquals(0.3f, MicLevel.fromDecibels(40f), 1e-6f)
        assertEquals(0.15f, MicLevel.fromDecibels(5f), 1e-6f)
    }
}
