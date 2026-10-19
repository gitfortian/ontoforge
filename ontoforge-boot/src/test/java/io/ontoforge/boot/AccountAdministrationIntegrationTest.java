package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.dao.platform.LocalAccountRepository;
import io.ontoforge.platform.identity.yak.LocalPasswordEncoder;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ontoforge.security.authentication-mode=local-session","ontoforge.security.bootstrap.enabled=true",
        "ontoforge.security.bootstrap.username=account-admin-tests"})
@ActiveProfiles("integration-test")
@ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
class AccountAdministrationIntegrationTest {
    static final String PASSWORD="test-only-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("ontoforge.security.bootstrap.password",()->PASSWORD);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
    @Autowired LocalAccountRepository accounts;
    UUID adminId,userId,userPrincipal;
    String adminName,userName;
    record Seed(UUID account,UUID principal,String username) {}
    Seed seed(boolean admin) {
        UUID id=UUID.randomUUID(),principal=UUID.randomUUID();String username="test."+id;
        accounts.insert(id,principal,username,"Test Account",new LocalPasswordEncoder().encode(PASSWORD),admin);
        return new Seed(id,principal,username);
    }
    @BeforeEach void prepare() {
        Seed admin=seed(true),user=seed(false);
        adminId=admin.account();adminName=admin.username();userId=user.account();userName=user.username();userPrincipal=user.principal();
    }
    final class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf,header;
        HttpResponse<String> request(String method,String path,Object body) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if(csrf!=null && !method.equals("GET"))request.header(header,csrf);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void context() throws Exception {
            JsonNode value=json(request("GET","/api/auth/context",null),200).path("csrf");
            csrf=value.path("token").asText();header=value.path("headerName").asText();
        }
        void login(String username,String password) throws Exception {
            context();json(request("POST","/api/auth/login",Map.of("username",username,"password",password)),200);context();
        }
    }
    JsonNode json(HttpResponse<String> response,int expected) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return response.body().isEmpty()?mapper.nullNode():mapper.readTree(response.body());
    }
    Browser administrator() throws Exception { Browser browser=new Browser();browser.login(adminName,PASSWORD);return browser; }
    Map<String,Object> update(long version,boolean enabled,boolean admin) {
        return Map.of("expectedVersion",version,"displayName","Updated Account","enabled",enabled,"systemAdmin",admin);
    }
    @Test void normal_user_cannot_list_create_update_or_reset_another_account() throws Exception {
        Browser user=new Browser();user.login(userName,PASSWORD);
        assertThat(json(user.request("GET","/api/auth/context",null),200).path("capabilities").path("manageAccounts").asBoolean()).isFalse();
        json(user.request("GET","/api/platform/accounts",null),403);
        json(user.request("POST","/api/platform/accounts",Map.of("username","forged.admin","displayName","Forged","password",PASSWORD,"systemAdmin",true)),403);
        json(user.request("PUT","/api/platform/accounts/"+adminId,update(1,false,false)),403);
        json(user.request("POST","/api/platform/accounts/"+adminId+"/password",Map.of("expectedVersion",1,"password",PASSWORD)),403);
        assertThat(accounts.byId(adminId).systemAdmin()).isTrue();
    }
    @Test void administrator_creates_edits_lists_and_rejects_stale_versions_without_leaking_hashes() throws Exception {
        Browser admin=administrator();String username="new."+UUID.randomUUID();
        var input=Map.of("username",username.toUpperCase(),"displayName","New User","password",PASSWORD,"systemAdmin",false);
        JsonNode created=json(admin.request("POST","/api/platform/accounts",input),201);
        assertThat(created.path("username").asText()).isEqualTo(username);
        assertThat(created.toString()).doesNotContain("password","credentialVersion",PASSWORD);
        String id=created.path("accountId").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM b0_workspace_membership WHERE principal_id=?",Long.class,UUID.fromString(created.path("principalId").asText()))).isZero();
        json(admin.request("POST","/api/platform/accounts",input),409);
        JsonNode updated=json(admin.request("PUT","/api/platform/accounts/"+id,update(1,true,false)),200);
        assertThat(updated.path("version").asLong()).isEqualTo(2);
        json(admin.request("PUT","/api/platform/accounts/"+id,update(1,false,true)),409);
        JsonNode page=json(admin.request("GET","/api/platform/accounts?q="+username+"&page=1&pageSize=10",null),200);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("displayName").asText()).isEqualTo("Updated Account");
        assertThat(page.toString()).doesNotContain("passwordHash",PASSWORD);
        json(admin.request("GET","/api/platform/accounts?pageSize=101",null),400);
    }
    @Test void reset_password_and_soft_disable_revoke_real_existing_sessions() throws Exception {
        Browser admin=administrator(),user=new Browser();user.login(userName,PASSWORD);
        String replacement="replacement-"+UUID.randomUUID();
        json(admin.request("POST","/api/platform/accounts/"+userId+"/password",Map.of("expectedVersion",1,"password",replacement)),204);
        json(user.request("GET","/api/me",null),401);
        user.login(userName,replacement);
        json(admin.request("PUT","/api/platform/accounts/"+userId,update(2,false,false)),200);
        json(user.request("GET","/api/me",null),401);
        assertThat(accounts.byId(userId)).isNotNull();
        json(admin.request("PUT","/api/platform/accounts/"+userId,update(3,true,false)),200);
        user.login(userName,replacement);
    }
    @Test void user_changes_only_own_password_and_current_and_other_sessions_end() throws Exception {
        Browser user=new Browser(),other=new Browser();user.login(userName,PASSWORD);other.login(userName,PASSWORD);
        String replacement="self-change-"+UUID.randomUUID();
        json(user.request("POST","/api/auth/password",Map.of("currentPassword",PASSWORD,"newPassword",replacement,"accountId",adminId)),204);
        json(user.request("GET","/api/me",null),401);json(other.request("GET","/api/me",null),401);
        user.login(userName,replacement);
        assertThat(accounts.byId(adminId).credentialVersion()).isEqualTo(1);
    }
    @Test void last_local_workspace_owner_must_transfer_before_disable() throws Exception {
        Browser admin=administrator();UUID workspace=UUID.randomUUID();
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?)",workspace,"owner-"+workspace,"Owner Test");
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'OWNER')",workspace,userPrincipal);
        JsonNode blocked=json(admin.request("PUT","/api/platform/accounts/"+userId,update(1,false,false)),409);
        assertThat(blocked.path("code").asText()).isEqualTo("LAST_WORKSPACE_OWNER");
        Seed next=seed(false);
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'OWNER')",workspace,next.principal());
        json(admin.request("PUT","/api/platform/accounts/"+userId,update(1,false,false)),200);
    }
    @Test void concurrent_admin_demotion_cannot_remove_both_administrators() throws Exception {
        Seed second=seed(true);Browser first=administrator(),other=new Browser();other.login(second.username(),PASSWORD);
        CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a=pool.submit(()->{start.await();return first.request("PUT","/api/platform/accounts/"+second.account(),update(1,true,false)).statusCode();});
            Future<Integer> b=pool.submit(()->{start.await();return other.request("PUT","/api/platform/accounts/"+adminId,update(1,true,false)).statusCode();});
            start.countDown();List<Integer> statuses=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(status->status==200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(status->status==401||status==403).count()).isEqualTo(1);
            assertThat((accounts.byId(adminId).systemAdmin()?1:0)+(accounts.byId(second.account()).systemAdmin()?1:0)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        Browser winner=accounts.byId(adminId).systemAdmin()?first:other;
        UUID winnerId=accounts.byId(adminId).systemAdmin()?adminId:second.account();
        json(winner.request("PUT","/api/platform/accounts/"+winnerId,update(1,false,false)),409);
    }
}
