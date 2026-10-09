package cat.rumb.app.data.update

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UpdateRepositoryTest {

    @Test
    fun comparesSemanticVersions() {
        assertThat(UpdateRepository.compareVersions("0.3.0", "0.2.0")).isGreaterThan(0)
        assertThat(UpdateRepository.compareVersions("0.2.0", "0.10.0")).isLessThan(0)
        assertThat(UpdateRepository.compareVersions("1.0.0", "1.0.0")).isEqualTo(0)
        assertThat(UpdateRepository.compareVersions("1.2", "1.2.0")).isEqualTo(0)
        assertThat(UpdateRepository.compareVersions("2.0.1", "2.0.0")).isGreaterThan(0)
    }
}
