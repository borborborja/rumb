package cat.rumb.app.data.premium

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PremiumQueryLifecycleTest {
    @Test fun disconnectAllowsANewQueryEvenIfTheOldQueryNeverCallsBack() {
        val queries = PremiumQueryLifecycle()
        queries.begin()
        queries.invalidate()

        assertThat(queries.isInFlight).isFalse()
        assertThat(queries.begin()).isNotNull()
    }

    @Test fun oldConnectionCannotGrantPremiumOrCompleteTheNewQuery() {
        val queries = PremiumQueryLifecycle()
        val old = queries.begin()!!
        queries.invalidate()
        val current = queries.begin()!!
        var hasPremium = false

        if (queries.complete(old)) hasPremium = true

        assertThat(hasPremium).isFalse()
        assertThat(queries.isInFlight).isTrue()
        assertThat(queries.complete(current)).isTrue()
        assertThat(queries.isInFlight).isFalse()
    }

    @Test fun aNewPriceQueryRejectsTheOlderPriceCallback() {
        val queries = PremiumQueryLifecycle()
        val old = queries.begin()!!
        val current = queries.begin(supersede = true)!!
        var price = ""

        if (queries.complete(old)) price = "old price"
        if (queries.complete(current)) price = "current price"

        assertThat(price).isEqualTo("current price")
    }

    @Test fun duplicateCompletionOrJoinCannotClearAnActiveRequest() {
        val queries = PremiumQueryLifecycle()
        val request = queries.begin()!!
        assertThat(queries.begin()).isNull()
        assertThat(queries.isInFlight).isTrue()
        assertThat(queries.complete(request)).isTrue()
        assertThat(queries.complete(request)).isFalse()
    }
}
