package ai.opencyclone.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPhrasesTest {
    private val known = listOf("gpt-5.6-terra", "gpt-6.1-sol")

    @Test fun parsesUseModel() {
        assertEquals(ModelPhrase.Use("gpt-6.1-sol"), ModelPhrases.parse("use model gpt-6.1-sol"))
        assertEquals(ModelPhrase.Use("GPT 5.6 Terra"), ModelPhrases.parse("Use the model GPT 5.6 Terra."))
        assertEquals(ModelPhrase.Use("x"), ModelPhrases.parse("switch to model x"))
    }

    @Test fun parsesWhichModel() {
        assertEquals(ModelPhrase.Which, ModelPhrases.parse("which model"))
        assertEquals(ModelPhrase.Which, ModelPhrases.parse("Which model are you using?"))
        assertEquals(ModelPhrase.Which, ModelPhrases.parse("what model"))
    }

    @Test fun ignoresOtherSentences() {
        assertNull(ModelPhrases.parse("use model"))
        assertNull(ModelPhrases.parse("send the photos to Alex"))
        assertNull(ModelPhrases.parse("which model of phone is best"))
        assertNull(ModelPhrases.parse("open the model app"))
    }

    @Test fun resolvesSpokenNamesToKnownIds() {
        assertEquals("gpt-5.6-terra", ModelPhrases.resolve("GPT 5.6 Terra", known))
        assertEquals("gpt-5.6-terra", ModelPhrases.resolve("gpt 5 point 6 terra", known))
        assertEquals("gpt-6.1-sol", ModelPhrases.resolve("GPT-6.1-SOL", known))
        assertEquals("my-model", ModelPhrases.resolve(" my-model ", known))
    }
}
