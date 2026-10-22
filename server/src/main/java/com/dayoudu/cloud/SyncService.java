package com.dayoudu.cloud;

import static com.dayoudu.cloud.ApiError.fail;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Service
public class SyncService {
    volatile boolean ready;
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ready() { ready=true; }
    final JdbcTemplate db;
    final ObjectMapper json;
    final TransactionTemplate tx;
    final RecordValidator validator;
    SyncService(JdbcTemplate db, ObjectMapper json, TransactionTemplate tx, RecordValidator validator) {
        this.db=db; this.json=json; this.tx=tx; this.validator=validator;
    }
    JsonNode parse(String text) {
        try { return json.readTree(text); } catch (Exception e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw fail(400, "JSON 格式无效"); }
    }
    JsonNode find(String owner, String kind, String id) {
        var rows = db.queryForList("SELECT payload,server_version FROM cloud_records WHERE owner_id=? AND kind=? AND record_id=?", owner,kind,id);
        return rows.isEmpty() ? null : envelope(kind,id, ((Number)rows.get(0).get("server_version")).longValue(),parse((String)rows.get(0).get("payload")));
    }
    ObjectNode envelope(String kind, String id, long version, JsonNode payload) {
        payload=payload.deepCopy();
        // v1 stored ledger rows acquire nullable v2 fields when read, without rewriting history.
        if(kind.equals("ledger")) for(JsonNode row:payload.path("tables").path("ledger_entries")) if(row instanceof ObjectNode object) {
            if(!object.has("category")) object.putNull("category");
            if(!object.has("note")) object.putNull("note");
        }
        var result=json.createObjectNode().put("kind",kind).put("id",id).put("serverVersion",version);
        result.set("payload",payload); return result;
    }
    public JsonNode write(String owner, JsonNode request) {
        if (request == null || !request.isObject() || !fields(request).equals(Set.of("operationId","kind","id","expectedVersion","payload"))) throw fail(400,"操作字段不匹配");
        String operation=uuid(request.path("operationId")), id=uuid(request.path("id"));
        String kind=request.path("kind").asText();
        if (!request.path("expectedVersion").isIntegralNumber() || !request.path("expectedVersion").canConvertToLong()) throw fail(400,"expectedVersion 无效");
        long expected=request.path("expectedVersion").longValue();
        if (expected<0) throw fail(400,"expectedVersion 无效");
        if (encode(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_000_000) throw fail(413,"单条记录过大，请分批操作");
        return tx.execute(status -> {
            // Per-account lock serializes version checks AND committed change ordering.
            long seq=db.queryForObject("SELECT change_seq FROM cloud_accounts WHERE id=? FOR UPDATE",Long.class,owner);
            var prior=db.queryForList("SELECT request_json,response_json FROM cloud_operations WHERE owner_id=? AND operation_id=?",owner,operation);
            if (!prior.isEmpty()) {
                if (!parse((String)prior.get(0).get("request_json")).equals(request)) throw fail(409,"operationId 已被其他请求使用");
                return parse((String)prior.get(0).get("response_json"));
            }
            JsonNode current=find(owner,kind,id);
            if(kind.equals("category")||kind.equals("project")) {
                String table=kind.equals("category")?"time_categories":"projects";
                String name=request.path("payload").path("tables").path(table).path(0).path("name").asText();
                for(var candidate:db.queryForList("SELECT record_id,payload FROM cloud_records WHERE owner_id=? AND kind=? AND record_id<>?",owner,kind,id)) {
                    if(parse((String)candidate.get("payload")).path("tables").path(table).path(0).path("name").asText().equals(name))
                        throw new ApiError.Failure(409,"云端已有同名分类或项目，请合并云端版本",find(owner,kind,(String)candidate.get("record_id")));
                }
            }
            long version=current==null ? 0 : current.path("serverVersion").longValue();
            if (version!=expected) throw new ApiError.Failure(409,"记录已在云端变化",current==null ? Map.of() : current);
            validator.validate(kind,id,request.path("payload"),current==null?null:current.path("payload"),
                (k,key) -> { JsonNode record=find(owner,k,key); return record==null?null:record.path("payload"); },
                () -> db.queryForList("SELECT payload FROM cloud_records WHERE owner_id=? AND kind=? AND record_id<>?",String.class,owner,kind,id).stream().map(this::parse).toList());
            String payload=encode(request.path("payload"));
            if (version==0) db.update("INSERT INTO cloud_records(owner_id,kind,record_id,server_version,payload) VALUES(?,?,?,?,?)",owner,kind,id,1,payload);
            else db.update("UPDATE cloud_records SET server_version=?,payload=? WHERE owner_id=? AND kind=? AND record_id=?",version+1,payload,owner,kind,id);
            db.update("UPDATE cloud_accounts SET change_seq=? WHERE id=?",seq+1,owner);
            db.update("INSERT INTO cloud_changes(owner_id,seq,kind,record_id,server_version,payload) VALUES(?,?,?,?,?,?)",owner,seq+1,kind,id,version+1,payload);
            ObjectNode response=envelope(kind,id,version+1,request.path("payload")).put("seq",seq+1);
            db.update("INSERT INTO cloud_operations(owner_id,operation_id,request_json,response_json) VALUES(?,?,?,?)",owner,operation,encode(request),encode(response));
            return response;
        });
    }
    Map<String,Object> changes(String owner,long cursor,int limit) {
        if(cursor<0 || limit<1 || limit>20) throw fail(400,"分页参数无效");
        var rows=db.queryForList("SELECT seq,kind,record_id,server_version,payload FROM cloud_changes WHERE owner_id=? AND seq>? ORDER BY seq LIMIT ?",owner,cursor,limit);
        List<JsonNode> data=new ArrayList<>(); long next=cursor;
        for(var row:rows) {
            next=((Number)row.get("seq")).longValue();
            data.add(envelope((String)row.get("kind"),(String)row.get("record_id"),((Number)row.get("server_version")).longValue(),parse((String)row.get("payload"))).put("seq",next));
        }
        return Map.of("items",data,"nextCursor",next,"hasMore",rows.size()==limit);
    }
    static Set<String> fields(JsonNode node) { Set<String> result=new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    static String uuid(JsonNode value) {
        if (!value.isTextual()) throw fail(400,"ID 必须为 UUID");
        try { String id=value.textValue(); if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); return id; }
        catch(Exception e) { throw fail(400,"ID 必须为规范 UUID"); }
    }
    @RestController
    static class Controller {
        final SyncService service;
        Controller(SyncService service){this.service=service;}
        @GetMapping("/health") Map<String,Object> health(){if(!service.ready)throw fail(503,"服务正在启动");service.db.queryForObject("SELECT 1",Integer.class);return Map.of("status","ok");}
        @GetMapping("/api/v1/sync/contract") JsonNode contract(){return service.validator.contract;}
        @PostMapping("/api/v1/sync/operations") JsonNode write(Principal owner,@RequestBody JsonNode request){return service.write(owner.getName(),request);}
        @GetMapping("/api/v1/sync/changes") Map<String,Object> changes(Principal owner,@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="5") int limit){return service.changes(owner.getName(),cursor,limit);}
        @GetMapping("/api/v1/records/{kind}/{id}") JsonNode get(Principal owner,@PathVariable String kind,@PathVariable String id) {
            var record=service.find(owner.getName(),kind,id);if(record==null)throw fail(404,"记录不存在");return record;
        }
    }
}
