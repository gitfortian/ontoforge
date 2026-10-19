package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.business.model.NativeDraftService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ontoforge.security.authentication-mode=local-session","ontoforge.security.bootstrap.enabled=true",
        "ontoforge.security.bootstrap.username=workspace-tests"})
@ActiveProfiles("integration-test")
@ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
class WorkspaceIntegrationTest {
    static final String PASSWORD="test-only-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {registry.add("ontoforge.security.bootstrap.password",()->PASSWORD);}
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
    @Autowired LocalAccountRepository accounts;
    @Autowired NativeDraftService drafts;
    @Autowired PlatformTransactionManager transactions;
    record Seed(UUID account,UUID principal,String username) {}
    Seed owner,peer,admin;
    Seed seed(boolean administrator) {
        UUID id=UUID.randomUUID(),principal=UUID.randomUUID();String username="space."+id;
        accounts.insert(id,principal,username,username,new LocalPasswordEncoder().encode(PASSWORD),administrator);
        return new Seed(id,principal,username);
    }
    @BeforeEach void prepare() {owner=seed(false);peer=seed(false);admin=seed(true);}
    final class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token,header;
        HttpResponse<String> request(String method,String path,Object body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if(token!=null&&!method.equals("GET"))builder.header(header,token);
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {
            var csrf=json(request("GET","/api/auth/context",null),200).path("csrf");token=csrf.path("token").asText();header=csrf.path("headerName").asText();
        }
        void login(Seed seed) throws Exception {
            csrf();json(request("POST","/api/auth/login",Map.of("username",seed.username(),"password",PASSWORD)),200);csrf();
        }
    }
    Browser login(Seed seed) throws Exception {Browser browser=new Browser();browser.login(seed);return browser;}
    JsonNode json(HttpResponse<String> response,int expected) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return response.body().isEmpty()?mapper.nullNode():mapper.readTree(response.body());
    }
    JsonNode create(Browser browser) throws Exception {
        return json(browser.request("POST","/api/platform/workspaces",Map.of("workspaceKey","ws-"+UUID.randomUUID(),"displayName","Working Space")),201);
    }
    String path(JsonNode workspace) {return "/api/platform/workspaces/"+workspace.path("workspaceId").asText();}
    String packageId() {return "workspace.p"+UUID.randomUUID().toString().replace("-","");}
    @Test void create_owner_rename_and_outsider_cannot_manage_or_browse() throws Exception {
        Browser one=login(owner),other=login(peer);JsonNode workspace=create(one);String path=path(workspace);
        assertThat(workspace.path("roles").get(0).asText()).isEqualTo("OWNER");assertThat(workspace.path("packageIds").isEmpty()).isTrue();
        json(other.request("GET",path,null),403);json(other.request("GET",path+"/members",null),403);
        json(other.request("PUT",path,Map.of("expectedVersion",1,"displayName","spoofed")),403);
        JsonNode renamed=json(one.request("PUT",path,Map.of("expectedVersion",1,"displayName","Renamed")),200);
        assertThat(renamed.path("version").asLong()).isEqualTo(2);assertThat(renamed.path("workspaceKey")).isEqualTo(workspace.path("workspaceKey"));
        json(one.request("PUT",path,Map.of("expectedVersion",1,"displayName","stale")),409);
        assertThat(json(other.request("GET","/api/platform/workspaces",null),200).toString()).doesNotContain(workspace.path("workspaceId").asText());
    }
    @Test void members_use_existing_roles_and_revoke_the_actual_draft_api() throws Exception {
        Browser one=login(owner),other=login(peer);JsonNode workspace=create(one);String path=path(workspace),pkg=packageId();
        json(one.request("POST",path+"/packages",Map.of("packageId",pkg)),200);
        var draft=json(one.request("POST","/api/model/drafts",Map.of("packageId",pkg)),201);String draftPath="/api/model/drafts/"+draft.path("draftId").asText();
        json(other.request("GET",draftPath,null),403);
        var search=json(one.request("GET",path+"/members?q="+peer.username(),null),200);
        assertThat(search.path("candidates").get(0).path("principalId").asText()).isEqualTo(peer.principal().toString());
        json(one.request("PUT",path+"/members/"+peer.principal(),Map.of("expectedVersion",2,"roles",List.of("EDITOR"))),200);
        json(other.request("GET",draftPath,null),200);
        json(other.request("PUT",path+"/members/"+owner.principal(),Map.of("expectedVersion",3,"roles",List.of("VIEWER"))),403);
        json(one.request("PUT",path+"/members/"+peer.principal(),Map.of("expectedVersion",3,"roles",List.of("REVIEWER"))),200);
        json(other.request("GET",draftPath,null),200);
        json(other.request("POST",draftPath+"/validate",Map.of("expectedRevision",1)),403);
        json(one.request("DELETE",path+"/members/"+peer.principal()+"?expectedVersion=4",null),204);
        json(other.request("GET",draftPath,null),403);
    }
    @Test void last_owner_is_protected_and_an_owner_can_leave_after_transfer() throws Exception {
        Browser one=login(owner),other=login(peer);String path=path(create(one));
        json(one.request("DELETE",path+"/members/"+owner.principal()+"?expectedVersion=1",null),409);
        json(one.request("PUT",path+"/members/"+owner.principal(),Map.of("expectedVersion",1,"roles",List.of("EDITOR"))),409);
        json(one.request("PUT",path+"/members/"+peer.principal(),Map.of("expectedVersion",1,"roles",List.of("OWNER"))),200);
        json(one.request("DELETE",path+"/members/"+owner.principal()+"?expectedVersion=2",null),204);
        json(one.request("GET",path+"/members",null),403);json(other.request("GET",path+"/members",null),200);
        json(other.request("PUT",path+"/members/"+owner.principal(),Map.of("expectedVersion",1,"roles",List.of("OWNER"))),409);
    }
    @Test void historical_adoption_is_explicit_admin_only_and_package_ownership_is_unique() throws Exception {
        Browser one=login(owner),administrator=login(admin);JsonNode first=create(one),second=create(one);String pkg=packageId();
        var historical=drafts.create(pkg);
        json(one.request("POST",path(first)+"/packages",Map.of("packageId",pkg)),403);
        json(administrator.request("POST",path(first)+"/packages",Map.of("packageId",pkg)),200);
        json(administrator.request("GET","/api/model/drafts/"+historical.draftId(),null),403);
        json(one.request("GET","/api/model/drafts/"+historical.draftId(),null),200);
        json(one.request("POST",path(second)+"/packages",Map.of("packageId",pkg)),409);
        JsonNode repeat=json(one.request("POST",path(first)+"/packages",Map.of("packageId",pkg)),200);
        assertThat(repeat.path("version").asLong()).isEqualTo(2);
        json(one.request("POST",path(first)+"/packages",Map.of("packageId","Invalid/Id")),400);
    }
    @Test void workspace_filter_keeps_native_cursor_pages_within_the_selected_authorized_space() throws Exception {
        Browser one=login(owner),other=login(peer);JsonNode a=create(one),b=create(one);String pkgA=packageId(),pkgB=packageId();
        json(one.request("POST",path(a)+"/packages",Map.of("packageId",pkgA)),200);json(one.request("POST",path(b)+"/packages",Map.of("packageId",pkgB)),200);
        json(one.request("POST","/api/model/drafts",Map.of("packageId",pkgA)),201);json(one.request("POST","/api/model/drafts",Map.of("packageId",pkgB)),201);
        String list="/api/model/drafts?workspaceId="+a.path("workspaceId").asText();
        JsonNode filtered=json(one.request("GET",list,null),200);
        assertThat(filtered.path("items").size()).isEqualTo(1);assertThat(filtered.path("items").get(0).path("packageId").asText()).isEqualTo(pkgA);
        json(other.request("GET",list,null),403);
    }
    @Test void concurrent_membership_updates_use_the_workspace_revision() throws Exception {
        Browser one=login(owner);String path=path(create(one));Seed next=seed(false);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return one.request("PUT",path+"/members/"+peer.principal(),Map.of("expectedVersion",1,"roles",List.of("EDITOR"))).statusCode();});
            var second=pool.submit(()->{start.await();return one.request("PUT",path+"/members/"+next.principal(),Map.of("expectedVersion",1,"roles",List.of("EDITOR"))).statusCode();});
            start.countDown();assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(json(one.request("GET",path+"/members",null),200).path("members").size()).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
    @Test void owner_allocation_waits_for_inflight_historical_creation_and_then_refuses_adoption() throws Exception {
        Browser one=login(owner);String path=path(create(one)),pkg=packageId();
        var pool=Executors.newFixedThreadPool(2);var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var nativeWrite=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                drafts.create(pkg);inserted.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test release timed out");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return true;
            }));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
            var allocation=pool.submit(()->one.request("POST",path+"/packages",Map.of("packageId",pkg)));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);long waiting=0;
            while(waiting==0&&System.nanoTime()<until) {
                waiting=jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND pid IN (SELECT pid FROM pg_stat_activity WHERE datname=current_database())",Long.class);
                if(waiting==0)Thread.sleep(20);
            }
            assertThat(waiting).as("Real PostgreSQL package lock must block allocation until the existing model commits").isPositive();
            release.countDown();assertThat(nativeWrite.get(5,TimeUnit.SECONDS)).isTrue();json(allocation.get(5,TimeUnit.SECONDS),403);
        } finally {release.countDown();pool.shutdownNow();}
    }
}
