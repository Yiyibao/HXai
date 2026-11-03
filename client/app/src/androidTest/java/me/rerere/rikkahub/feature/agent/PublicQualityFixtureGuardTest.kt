package me.rerere.rikkahub.feature.agent

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class PublicQualityFixtureGuardTest {
    private fun provider(base: String="http://127.0.0.1:18888/v1",key: String="synthetic",model: String="qa-shared-math") =
        ProviderSetting.OpenAI(name="QA shared math",baseUrl=base,apiKey=key,models=listOf(Model(modelId=model)))
    @Test fun onlyTheKnownLoopbackFixtureIsRecognized() {
        assertTrue(isKnownLocalQualityFixture(provider()))
    }
    @Test fun aRealKeyOrModelCannotBeTreatedAsAFixture() {
        assertFalse(isKnownLocalQualityFixture(provider(key="different-test-key")))
        assertFalse(isKnownLocalQualityFixture(provider(model="gpt-6.1-terra")))
    }
    @Test fun externalOrDecoratedEndpointsCannotBeTreatedAsFixtures() {
        for(url in listOf("https://example.org/v1","http://localhost:18888/v1","http://127.0.0.1:18888/v1?x=1",
            "http://user@127.0.0.1:18888/v1","http://127.0.0.1:18888/v1#fragment"))
            assertFalse(isKnownLocalQualityFixture(provider(base=url)))
    }
}
