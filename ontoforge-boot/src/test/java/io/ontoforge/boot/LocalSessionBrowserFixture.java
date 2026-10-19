package io.ontoforge.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

/** Test-classpath-only setup for actual password/Cookie browser stories; never in Boot jar. */
public final class LocalSessionBrowserFixture {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("TEST_DB_URL");
        IsolatedPostgresTestDatabase.requireIsolatedUrl(url);
        if(args.length!=2 || !java.util.Set.of("prepare","provision").contains(args[0]))
            throw new IllegalArgumentException("Usage: prepare|provision <private file>");
        var mapper=new ObjectMapper();Path path=Path.of(args[1]);
        if(args[0].equals("prepare")) {
            var fixture=mapper.createObjectNode().put("username","e2e-local-admin")
                    .put("password","test-only-"+UUID.randomUUID()).put("workspaceId",UUID.randomUUID().toString());
            var packages=fixture.putArray("packages");
            for(int i=0;i<16;i++)packages.add("session.e2e.p"+UUID.randomUUID().toString().replace("-",""));
            Files.writeString(path,mapper.writeValueAsString(fixture));
        } else {
            var fixture=mapper.readTree(Files.readString(path));
            try(var db=DriverManager.getConnection(url,System.getenv("TEST_DB_USERNAME"),System.getenv("TEST_DB_PASSWORD"))) {
                db.setAutoCommit(false);
                UUID principal;
                try(var select=db.prepareStatement("SELECT principal_id FROM platform_local_account WHERE username=?")) {
                    select.setString(1,fixture.path("username").asText());
                    try(var row=select.executeQuery()) { if(!row.next())throw new IllegalStateException("Bootstrap account missing");principal=row.getObject(1,UUID.class); }
                }
                UUID workspace=UUID.fromString(fixture.path("workspaceId").asText());
                try(var insert=db.prepareStatement("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?)")) {
                    insert.setObject(1,workspace);insert.setString(2,"session-e2e-"+workspace);insert.setString(3,"Session browser test");insert.executeUpdate();
                }
                try(var insert=db.prepareStatement("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'OWNER')")) {
                    insert.setObject(1,workspace);insert.setObject(2,principal);insert.executeUpdate();
                }
                for(var value:fixture.path("packages")) {
                    try(var insert=db.prepareStatement("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)")) {
                        insert.setObject(1,workspace);insert.setString(2,value.asText());insert.executeUpdate();
                    }
                }
                db.commit();
            }
        }
        System.out.println("Isolated Session browser fixture ready (credentials not logged).");
    }
}
