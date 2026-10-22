package com.dayoudu.cloud;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
/** Standalone JRE-only probe; does not start another Spring application. */
public class HealthProbe {
    public static void main(String[] args) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var response=client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/health")).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=200)System.exit(1);
        if(args.length>0&&args[0].equals("authenticated")) {
            String body="{\"uid\":"+quote(System.getenv("DAILY_USERNAME"))+",\"password\":"+quote(System.getenv("DAILY_PASSWORD"))+"}";
            var login=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if(login.statusCode()!=200)throw new IllegalStateException("Restore authentication failed");
            var matcher=java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").matcher(login.body());
            if(!matcher.find())throw new IllegalStateException("Missing restore test session");
            var records=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/sync/changes?cursor=0&limit=1"))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+matcher.group(1)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(records.statusCode()!=200||!records.body().contains("\"items\""))throw new IllegalStateException("Restored API read failed");
            System.out.println("Restored authentication and business API read passed.");
        }
    }
    private static String quote(String value){if(value==null)throw new IllegalArgumentException("Missing restore credentials");return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
