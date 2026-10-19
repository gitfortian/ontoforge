package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.dao.platform.LocalAccountRepository;
import io.ontoforge.platform.identity.LocalAccountService;
import io.ontoforge.platform.identity.yak.LocalPasswordEncoder;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Cookie/CSRF HTTP clients, PostgreSQL grants and real ObjectSet pages; no mock login bypass. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ontoforge.security.authentication-mode=local-session","ontoforge.security.bootstrap.enabled=true",
        "ontoforge.security.bootstrap.username=session-bootstrap","server.servlet.session.timeout=5s"})
@ActiveProfiles("integration-test")
@ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
class LocalSessionIntegrationTest {
    private static final String PASSWORD="test-only-"+UUID.randomUUID();
    private static final String READER_PASSWORD="m5_test_reader_only";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("ontoforge.security.bootstrap.password",()->PASSWORD);
        registry.add("ontoforge.binding.source.url",()->System.getenv("TEST_DB_URL"));
        registry.add("ontoforge.binding.source.username",()->"ontoforge_binding_login");
        registry.add("ontoforge.binding.source.password",()->READER_PASSWORD);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
    @Autowired LocalAccountRepository accounts;
    @Autowired LocalAccountService accountService;
    @Autowired NativeDraftService drafts;
    @Autowired NativeSnapshotService snapshots;
    UUID accountId,principalId;
    String username;
    @BeforeEach void seed() {
        accountId=UUID.randomUUID();principalId=UUID.randomUUID();username="u."+accountId;
        accounts.insert(accountId,principalId,username,"Session User",new LocalPasswordEncoder().encode(PASSWORD),false);
        jdbc.execute("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ontoforge_binding_login') THEN
                    CREATE ROLE ontoforge_binding_login LOGIN PASSWORD 'm5_test_reader_only'
                      NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT;
                  END IF;
                END $$
                """);
        jdbc.execute("ALTER ROLE ontoforge_binding_login WITH LOGIN PASSWORD 'm5_test_reader_only' NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT");
        jdbc.execute("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM ontoforge_binding_login");
        jdbc.execute("GRANT ontoforge_binding_reader TO ontoforge_binding_login");
    }
    final class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf,header;
        HttpResponse<String> request(String method,String path,Object body,boolean withCsrf) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if(withCsrf && csrf!=null)builder.header(header,csrf);
            builder.header("Content-Type","application/json");
            return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode context() throws Exception {
            JsonNode value=json(request("GET","/api/auth/context",null,false),200);
            csrf=value.path("csrf").path("token").asText();header=value.path("csrf").path("headerName").asText();
            assertThat(csrf).isNotBlank();assertThat(header).isNotBlank();return value;
        }
        JsonNode login(String user) throws Exception {
            context();JsonNode identity=json(request("POST","/api/auth/login",Map.of("username",user,"password",PASSWORD),true),200);
            context();return identity;
        }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("ONTOFORGE_SESSION")).findFirst().orElseThrow().getValue(); }
    }
    private JsonNode json(HttpResponse<String> response,int expected) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return response.body().isEmpty()?mapper.nullNode():mapper.readTree(response.body());
    }
    @Test void a_cookie_rotation_csrf_login_real_identity_and_server_logout() throws Exception {
        Browser browser=new Browser();
        var anonymous=browser.request("GET","/api/auth/context",null,false);
        assertThat(anonymous.headers().firstValue("cache-control").orElse("")).contains("no-store");
        String cookie=anonymous.headers().allValues("set-cookie").toString();
        assertThat(cookie).contains("HttpOnly","SameSite=Lax","Path=/api");
        String before=browser.cookie();
        json(browser.request("POST","/api/auth/login",Map.of("username",username,"password",PASSWORD),false),403);
        JsonNode me=browser.login(" "+username.toUpperCase()+" ");
        assertThat(me.path("principalId").asText()).isEqualTo(principalId.toString());
        assertThat(me.path("issuer").asText()).isEqualTo("urn:ontoforge:local");
        assertThat(browser.cookie()).isNotEqualTo(before);
        assertThat(me.toString()).doesNotContain("password","passwordHash");
        assertThat(json(browser.request("GET","/api/me",null,false),200).path("subject").asText()).isEqualTo(accountId.toString());
        json(browser.request("POST","/api/auth/logout",null,false),403);
        json(browser.request("POST","/api/auth/logout",null,true),204);
        json(browser.request("GET","/api/me",null,false),401);
        browser.context();json(browser.request("POST","/api/auth/logout",null,true),204);
    }
    @Test void b_cross_session_csrf_and_bearer_are_not_session_identity() throws Exception {
        Browser one=new Browser(),two=new Browser();one.context();two.context();
        two.csrf=one.csrf;two.header=one.header;
        json(two.request("POST","/api/auth/login",Map.of("username",username,"password",PASSWORD),true),403);
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/me"))
                .header("Authorization","Bearer forged-session-bypass").build(),HttpResponse.BodyHandlers.ofString());
        json(response,401);
    }
    @Test void c_credentials_version_and_disable_revoke_an_existing_cookie_immediately() throws Exception {
        Browser browser=new Browser();browser.login(username);
        jdbc.update("UPDATE platform_local_account SET credential_version=credential_version+1 WHERE account_id=?",accountId);
        json(browser.request("POST","/api/model/drafts",Map.of("packageId","revoked.must.not.create"),true),401);
        json(browser.request("GET","/api/me",null,false),401);
        browser.login(username);
        jdbc.update("UPDATE platform_local_account SET enabled=false WHERE account_id=?",accountId);
        json(browser.request("GET","/api/me",null,false),401);
    }
    @Test void d_admin_does_not_implicitly_read_packages_and_grants_are_rechecked() throws Exception {
        Browser browser=new Browser();browser.login(username);
        String packageId="session.p"+UUID.randomUUID().toString().replace("-","");
        var draft=drafts.create(packageId);
        String path="/api/model/drafts/"+draft.draftId();
        jdbc.update("UPDATE platform_local_account SET system_admin=true WHERE account_id=?",accountId);
        json(browser.request("GET",path,null,false),403);
        UUID workspace=grant(packageId,principalId);
        json(browser.request("GET",path,null,false),200);
        jdbc.update("DELETE FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=?",workspace,principalId);
        json(browser.request("GET",path,null,false),403);
    }
    @Test void e_idle_timeout_rejects_the_old_cookie() throws Exception {
        Browser browser=new Browser();browser.login(username);Thread.sleep(6100);
        json(browser.request("GET","/api/me",null,false),401);
    }
    @Test void f_password_limits_and_bootstrap_are_explicit() {
        assertThatThrownBy(()->LocalAccountService.requirePassword("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalAccountService.requirePassword("界".repeat(25))).isInstanceOf(IllegalArgumentException.class);
        long before=accounts.count();
        accountService.bootstrap(false,"ignored",null); // non-empty installs never reset an existing admin
        assertThat(accounts.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_local_account WHERE enabled AND system_admin",Long.class)).isPositive();
    }
    private UUID grant(String packageId,UUID principal) {
        UUID workspace=UUID.randomUUID();
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?)",workspace,"session-"+workspace,"Session Test");
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)",workspace,packageId);
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'EDITOR')",workspace,principal);
        return workspace;
    }
    @Test void g_two_session_users_cannot_share_objectset_cursors_and_revocation_is_terminal() throws Exception {
        String packageId="session.query.p"+UUID.randomUUID().toString().replace("-","");
        var draft=drafts.create(packageId);
        ObjectNode editor=(ObjectNode)mapper.readTree("""
                {"editorSchemaVersion":"0.1.0","objectTypes":[{"id":"supplier","apiName":"Supplier","label":"供应商",
                 "primaryKeyRef":"code","properties":[
                 {"id":"code","apiName":"code","label":"编号","valueType":"STRING","required":true,"searchable":true,"classification":"PUBLIC","enumValues":[]},
                 {"id":"name","apiName":"name","label":"名称","valueType":"STRING","required":true,"searchable":true,"classification":"PUBLIC","enumValues":[]},
                 {"id":"rating","apiName":"rating","label":"评分","valueType":"DECIMAL","required":false,"searchable":true,"classification":"PUBLIC","enumValues":[]}]}],"linkTypes":[]}
                """);
        var saved=drafts.save(UUID.fromString(draft.draftId()),draft.revision(),editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()),saved.revision()).status()).isEqualTo("VALID");
        String snapshotId=snapshots.freeze(UUID.fromString(draft.draftId()),saved.revision(),UUID.randomUUID()).snapshot().snapshotId();
        UUID workspace=grant(packageId,principalId);
        UUID secondId=UUID.randomUUID(),secondPrincipal=UUID.randomUUID();
        String secondUser="second."+secondId;
        accounts.insert(secondId,secondPrincipal,secondUser,"Second",new LocalPasswordEncoder().encode(PASSWORD),false);
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'EDITOR')",workspace,secondPrincipal);
        Browser one=new Browser(),two=new Browser();one.login(username);two.login(secondUser);
        ObjectNode binding=(ObjectNode)mapper.readTree("""
                {"objectTypeId":"supplier","sourceId":"local-pg-sandbox","schema":"public","table":"m5_supplier_source",
                 "sourceKey":"vendor_code","expectedRevision":0,"fieldMappings":[
                 {"propertyRef":"code","sourceColumn":"vendor_code","authority":"AUTHORITATIVE"},
                 {"propertyRef":"name","sourceColumn":"name","authority":"AUTHORITATIVE"},
                 {"propertyRef":"rating","sourceColumn":"rating","authority":"AUTHORITATIVE"}]}
                """);
        binding.put("snapshotId",snapshotId);
        binding.put("expectedSchemaFingerprint",json(one.request("POST","/api/runtime/bindings/preview",binding,true),200).path("source").path("schemaFingerprint").asText());
        String bindingId=json(one.request("POST","/api/runtime/bindings",binding,true),201).path("bindingId").asText();
        ObjectNode query=mapper.createObjectNode().put("queryVersion","local-query-v1");
        var root=query.putObject("rootTypeRef").put("packageId",packageId).put("objectTypeId","supplier");
        root.putObject("sourceRef").put("kind","LOCAL_SNAPSHOT").put("snapshotId",snapshotId);
        query.putObject("bindingRef").put("bindingId",bindingId).put("revision",1);
        query.putArray("steps").addObject().put("kind","PAGE").put("pageSize",1);
        JsonNode first=json(one.request("POST","/api/runtime/object-sets/query",query,true),200);
        assertThat(first.path("accessMode").asText()).isEqualTo("SESSION_AUTHENTICATED");
        String cursor=first.path("nextCursor").asText();assertThat(cursor).startsWith("cursor:");
        json(two.request("POST","/api/runtime/object-sets/pages",Map.of("cursor",cursor),true),403);
        json(one.request("POST","/api/runtime/object-sets/pages",Map.of("cursor",cursor),true),200);
        jdbc.update("DELETE FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=?",workspace,principalId);
        json(one.request("POST","/api/runtime/object-sets/pages",Map.of("cursor",cursor),true),403);
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'EDITOR')",workspace,principalId);
        json(one.request("POST","/api/runtime/object-sets/pages",Map.of("cursor",cursor),true),410);
    }
    @Test void z_uniform_invalid_credentials_and_rate_limit() throws Exception {
        Browser browser=new Browser();browser.context();
        JsonNode wrong=json(browser.request("POST","/api/auth/login",Map.of("username",username,"password","wrong-test-password"),true),401);
        jdbc.update("UPDATE platform_local_account SET enabled=false WHERE account_id=?",accountId);
        assertThat(json(browser.request("POST","/api/auth/login",Map.of("username",username,"password",PASSWORD),true),401)).isEqualTo(wrong);
        for(int i=0;i<3;i++)
            json(browser.request("POST","/api/auth/login",Map.of("username","missing-account","password",PASSWORD),true),401);
        JsonNode limited=json(browser.request("POST","/api/auth/login",Map.of("username","missing-account","password",PASSWORD),true),429);
        assertThat(limited.path("code").asText()).isEqualTo("LOGIN_RATE_LIMITED");
    }
}
