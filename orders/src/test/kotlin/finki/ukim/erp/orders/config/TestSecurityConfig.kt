package finki.ukim.erp.orders.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain

/**
 * The filter chain the Pact provider test runs behind, replacing [SecurityConfig] under the `test`
 * profile.
 *
 * Pact replays the interactions recorded in a contract exactly as the consumer sent them, and a
 * consumer records the request its own client makes - not the token its deployment happens to
 * attach. With the real chain in place every interaction would come back 401 and the run would
 * report a broken contract when what actually broke is the test setup.
 *
 * Two things keep this from being a hole in the service:
 *
 * - It is `@Profile("test")`, and the only thing that activates that profile is `@ActiveProfiles`
 *   on a test class. Nothing in application.yaml turns it on.
 * - It is a test source. It is compiled into target/test-classes and never packaged into the jar,
 *   so a deployment started with `test` active would not find it - it would find no chain at all
 *   and Boot's default (deny everything) would apply.
 *
 * It is also `@TestConfiguration` rather than `@Configuration`: that keeps it out of the component
 * scan by default, so it only takes effect where a test names it - which is why the provider test
 * imports it explicitly instead of it quietly applying to anything that happens to use the profile.
 */
@TestConfiguration
@Profile("test")
class TestSecurityConfig {

    @Bean
    fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            authorizeHttpRequests {
                authorize(anyRequest, permitAll)
            }
        }
        return http.build()
    }
}
