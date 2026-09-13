package finki.ukim.erp.inventory.web

import finki.ukim.erp.inventory.config.KeycloakRealmRoleConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/**
 * Who may do what, as a caller meets it.
 *
 * This runs under the *default* profile, deliberately: it is the only test in the suite that wants
 * the real [finki.ukim.erp.inventory.config.SecurityConfig] in front of it, since it is asserting
 * the security rather than working around it. The endpoint tests next door run under `test` with a
 * permissive chain, which is what keeps them about bodies and status codes.
 *
 * No Keycloak is involved. `jwt()` installs an already-decoded token with the authorities named
 * here, so nothing has to be signed or fetched - the authorities are the part the endpoint rules
 * read. That the realm's roles actually become those authorities is [realmRolesBecomeAuthorities]
 * below, on the converter itself.
 *
 * The ADMIN and SERVICE cases assert "not 403" rather than a success status. A 400 from a
 * reservation for a product that does not exist is the endpoint doing its job and means the
 * authorization let it through, which is what is under test; asserting 201 would mean building
 * fixtures for every rule and would fail for reasons that have nothing to do with roles.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun roles(vararg names: String) =
        jwt().authorities(names.map { SimpleGrantedAuthority("ROLE_$it") })

    // ------------------------------------------------------------------ no token at all

    @Test
    fun `reading the catalogue without a token is refused`() {
        mockMvc.perform(get("/api/products"))
            .andExpect { assertEquals(HttpStatus.UNAUTHORIZED.value(), it.response.status) }
    }

    @Test
    fun `reserving without a token is refused`() {
        mockMvc.perform(
            post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderRef":"Order:${UUID.randomUUID()}","lines":[]}"""),
        ).andExpect { assertEquals(HttpStatus.UNAUTHORIZED.value(), it.response.status) }
    }

    /**
     * The documentation says how to obtain a token, so it cannot itself require one; and Consul
     * polls the health endpoint unauthenticated to decide whether to keep advertising this
     * instance, so a 401 there would take the service out of the registry.
     *
     * The health check is asserted as "not 401" rather than 200 because what it answers depends on
     * what is up around it - in this test context, with no broker, it is a legitimate 503. Whether
     * the instance is healthy is not the question here; whether the check is reachable is.
     */
    @Test
    fun `the api description and the health check stay public`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect { assertEquals(HttpStatus.OK.value(), it.response.status) }
        mockMvc.perform(get("/actuator/health"))
            .andExpect { assertNotEquals(HttpStatus.UNAUTHORIZED.value(), it.response.status) }
    }

    // ------------------------------------------------------------------ reads

    @Test
    fun `any authenticated caller may read the catalogue and the ledger`() {
        for (path in listOf("/api/products", "/api/stock", "/api/reservations")) {
            mockMvc.perform(get(path).with(roles("CUSTOMER")))
                .andExpect { assertEquals(HttpStatus.OK.value(), it.response.status, path) }
        }
    }

    // ------------------------------------------------------------------ catalogue and ledger

    @Test
    fun `a customer may not change the catalogue`() {
        mockMvc.perform(
            post("/api/products")
                .with(roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sku":"SKU-AUTH-1","name":"Nope","unitOfMeasure":"piece"}"""),
        ).andExpect { assertEquals(HttpStatus.FORBIDDEN.value(), it.response.status) }
    }

    @Test
    fun `a service account may not change the catalogue either`() {
        mockMvc.perform(
            post("/api/products")
                .with(roles("SERVICE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sku":"SKU-AUTH-2","name":"Nope","unitOfMeasure":"piece"}"""),
        ).andExpect { assertEquals(HttpStatus.FORBIDDEN.value(), it.response.status) }
    }

    @Test
    fun `an administrator may change the catalogue`() {
        mockMvc.perform(
            post("/api/products")
                .with(roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sku":"SKU-AUTH-${UUID.randomUUID()}","name":"Authorized","unitOfMeasure":"piece"}"""),
        ).andExpect { assertEquals(HttpStatus.CREATED.value(), it.response.status) }
    }

    // ------------------------------------------------------------------ reservations

    @Test
    fun `a customer may reserve - orders relays their token`() {
        mockMvc.perform(
            post("/api/reservations")
                .with(roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"orderRef":"Order:${UUID.randomUUID()}",""" +
                        """"lines":[{"productId":"${UUID.randomUUID()}","quantity":1}]}""",
                ),
        ).andExpect { assertNotEquals(HttpStatus.FORBIDDEN.value(), it.response.status) }
    }

    @Test
    fun `a service account may reserve - orders acting on no user's behalf`() {
        mockMvc.perform(
            post("/api/reservations")
                .with(roles("SERVICE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"orderRef":"Order:${UUID.randomUUID()}",""" +
                        """"lines":[{"productId":"${UUID.randomUUID()}","quantity":1}]}""",
                ),
        ).andExpect { assertNotEquals(HttpStatus.FORBIDDEN.value(), it.response.status) }
    }

    /**
     * The per-product stock operations are the low-level counterpart of `/api/reservations`, and no
     * ordering flow goes near them - a customer's token has no business here even though the same
     * customer may reserve through the endpoint above.
     */
    @Test
    fun `a customer may not move stock in and out of reserve directly`() {
        mockMvc.perform(
            post("/api/stock/${UUID.randomUUID()}/reserve")
                .with(roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderRef":"Order:${UUID.randomUUID()}","quantity":1}"""),
        ).andExpect { assertEquals(HttpStatus.FORBIDDEN.value(), it.response.status) }
    }

    @Test
    fun `a service account may move stock in and out of reserve directly`() {
        mockMvc.perform(
            post("/api/stock/${UUID.randomUUID()}/reserve")
                .with(roles("SERVICE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderRef":"Order:${UUID.randomUUID()}","quantity":1}"""),
        ).andExpect { assertNotEquals(HttpStatus.FORBIDDEN.value(), it.response.status) }
    }

    // ------------------------------------------------------------------ the claim mapping

    /**
     * The other half of every test above: those authorities have to be what a real Keycloak token
     * actually produces. This is the whole reason inventory and orders can be written against the
     * same role names - both read them off `realm_access.roles` and uppercase them behind `ROLE_`.
     */
    @Test
    fun realmRolesBecomeAuthorities() {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("realm_access", mapOf("roles" to listOf("ADMIN", "customer")))
            .build()

        val authorities = KeycloakRealmRoleConverter().convert(jwt)!!.map { it.authority }

        assertTrue(authorities.containsAll(listOf("ROLE_ADMIN", "ROLE_CUSTOMER")), authorities.toString())
    }

    @Test
    fun `a token without the realm roles claim carries no authorities`() {
        val jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("nobody").build()

        assertTrue(KeycloakRealmRoleConverter().convert(jwt)!!.isEmpty())
    }
}
