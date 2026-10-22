package com.dayoudu.cloud;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordValidator validator;
    @Autowired JdbcTemplate db;
    @Autowired SyncService service;
    @Autowired Authentication authentication;
    String token,owner;
    @BeforeAll void login() throws Exception {
        var created=json.readTree(mvc.perform(post("/api/v1/auth/register").contentType("application/json")
            .content("{\"accountName\":\"同名账户\",\"password\":\"Testpw8!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(json.createObjectNode().put("uid",created.path("uid").asText()).put("password","Testpw8!").toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        token=session.path("token").asText();owner=session.path("ownerId").asText();
    }
    String id(){return UUID.randomUUID().toString();}
    ObjectNode row(String table){
        ObjectNode row=json.createObjectNode();
        validator.contract.path("tables").path(table).fields().forEachRemaining(f->{
            if(!f.getValue().path("required").asBoolean())row.putNull(f.getKey());
            else if(f.getValue().path("type").asText().equals("TEXT"))row.put(f.getKey(),"value");else row.put(f.getKey(),1);
        });return row;
    }
    ObjectNode payload(String kind,ObjectNode root){
        ObjectNode tables=json.createObjectNode();JsonNode spec=validator.contract.path("kinds").path(kind);
        tables.putArray(spec.path("root").asText()).add(root);spec.path("children").fieldNames().forEachRemaining(tables::putArray);
        return json.createObjectNode().set("tables",tables);
    }
    ObjectNode op(String kind,String id,long version,ObjectNode payload){
        var request=json.createObjectNode().put("operationId",id()).put("kind",kind).put("id",id).put("expectedVersion",version);request.set("payload",payload);return request;
    }
    JsonNode write(ObjectNode request) throws Exception {return json.readTree(mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(request.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    ObjectNode ledger(String id){return row("ledger_entries").put("id",id).put("timezone","Asia/Shanghai").put("type","EXPENSE").put("source","中文's 午餐").put("amount","12.34");}
    @Test void personalDeploymentCanCloseRegistrationWhileExistingAccountStillLogsIn() throws Exception {
        boolean before=authentication.registrationEnabled;
        long accounts=db.queryForObject("SELECT COUNT(*) FROM cloud_accounts",Long.class);
        try {
            authentication.registrationEnabled=false;
            mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content(json.createObjectNode().put("accountName","公开注册关闭测试").put("password","Testpw8!").toString()))
                .andExpect(status().isForbidden());
            assertEquals(accounts,db.queryForObject("SELECT COUNT(*) FROM cloud_accounts",Long.class));
            String uid=db.queryForObject("SELECT uid FROM cloud_accounts WHERE id=?",String.class,owner);
            mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("personal-direct-login");return req;})
                .contentType("application/json").content(json.createObjectNode().put("uid",uid).put("password","Testpw8!").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value(owner));
        } finally {authentication.registrationEnabled=before;}
    }
    @Test void prototypeLedgerFieldsAndNutritionTargetsRoundTrip() throws Exception {
        String ledgerId=id();
        JsonNode saved=write(op("ledger",ledgerId,0,payload("ledger",ledger(ledgerId).put("category","FOOD").put("note","午餐"))));
        assertEquals("FOOD",saved.path("payload").path("tables").path("ledger_entries").path(0).path("category").asText());
        assertEquals("午餐",saved.path("payload").path("tables").path("ledger_entries").path(0).path("note").asText());
        String dayId=UUID.nameUUIDFromBytes("nutrition-day:2026-10-22:Asia/Shanghai".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ObjectNode day=row("nutrition_days").put("id",dayId).put("date","2026-10-22").put("timezone","Asia/Shanghai")
            .put("budgetKcal","1570").put("exerciseKcal","230").put("proteinGoal","249").put("fatGoal","72").put("carbGoal","49");
        JsonNode result=write(op("nutritionDay",dayId,0,payload("nutritionDay",day)));
        assertEquals("1570",result.path("payload").path("tables").path("nutrition_days").path(0).path("budgetKcal").asText());
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(op("nutritionDay",dayId,1,payload("nutritionDay",day.put("budgetKcal","-1"))).toString())).andExpect(status().isUnprocessableEntity());
    }
    @Test void legacyLedgerOutboxRemainsIdempotentAndReturnsNullableNewFields() throws Exception {
        String ledgerId=id();ObjectNode legacy=ledger(ledgerId);legacy.remove(List.of("category","note"));
        ObjectNode request=op("ledger",ledgerId,0,payload("ledger",legacy));
        JsonNode first=write(request);assertEquals(first,write(request));
        JsonNode restored=service.find(owner,"ledger",ledgerId).path("payload").path("tables").path("ledger_entries").path(0);
        assertTrue(restored.has("category"));assertTrue(restored.path("category").isNull());
        assertTrue(restored.path("note").isNull());
    }
    @Test void rejectsUnauthenticatedAndWrongPassword() throws Exception {
        mvc.perform(get("/api/v1/sync/changes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"integration-owner\",\"password\":\"incorrect\"}")).andExpect(status().isUnauthorized());
    }
    @Test void bootstrapAccountGetsFirstNumericUidAndLegacyLoginStillWorks() throws Exception {
        var session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content("{\"uid\":\"integration-owner\",\"password\":\"Testpw8!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("00000001",session.path("uid").asText());
    }
    @Test void registrationCreatesSessionWithUniqueUidAndRepeatableNickname() throws Exception {
        var body=json.createObjectNode().put("accountName","相同昵称").put("password","Example12!");
        var registered=json.readTree(mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("registration-test");return req;})
            .contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String first=registered.path("uid").asText();
        assertTrue(first.matches("[0-9]{8}"));
        assertEquals("相同昵称",registered.path("accountName").asText());
        mvc.perform(get("/api/v1/account/me").header("Authorization","Bearer "+registered.path("token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.uid").value(first));
        var second=json.readTree(mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("registration-test");return req;})
            .contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(Integer.parseInt(first)+1,Integer.parseInt(second.path("uid").asText()));
        assertEquals("相同昵称",second.path("accountName").asText());
        assertNotEquals(registered.path("ownerId").asText(),second.path("ownerId").asText());
        body.put("accountName","");
        mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("registration-test");return req;})
            .contentType("application/json").content(body.toString()))
            .andExpect(status().isUnprocessableEntity());
        body.put("accountName","第三人").put("password","short");
        mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("registration-test");return req;})
            .contentType("application/json").content(body.toString()))
            .andExpect(status().isUnprocessableEntity());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cloud_accounts WHERE account_name='第三人'",Integer.class));
    }
    @Test void uidIdentifiesAccountWhileDisplayNamesAndPasswordsMayRepeat() throws Exception {
        var created=json.readTree(mvc.perform(post("/api/v1/auth/register").contentType("application/json")
            .content("{\"accountName\":\"同名账户\",\"password\":\"Testpw8!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String otherId=created.path("ownerId").asText(),otherUid=created.path("uid").asText();
        var second=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(json.createObjectNode().put("uid",otherUid).put("password","Testpw8!").toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(otherId,second.path("ownerId").asText());
        assertEquals("同名账户",second.path("accountName").asText());
        assertNotEquals(owner,otherId);
        mvc.perform(get("/api/v1/account/me").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value("同名账户"));
        mvc.perform(post("/api/v1/account/name").header("Authorization","Bearer "+token)
            .contentType("application/json").content("{\"accountName\":\"同名账户\"}"))
            .andExpect(status().isOk());
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update(
            "INSERT INTO cloud_accounts(id,uid,account_name,password_hash,change_seq) VALUES(?,?,?,?,0)",
            id(),otherUid,"另一个名字","hash"));
    }
    @Test void nicknameAndPasswordLoginHandlesDuplicateNamesWithoutChoosingWrongAccount() throws Exception {
        String name="昵称登录-"+id().substring(0,8);
        var first=json.readTree(mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("name-register-1");return req;})
            .contentType("application/json").content(json.createObjectNode().put("accountName",name).put("password","Firstpw8!").toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var second=json.readTree(mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("name-register-2");return req;})
            .contentType("application/json").content(json.createObjectNode().put("accountName",name).put("password","Secondpw8!").toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var login=json.createObjectNode().put("accountName",name).put("password","Secondpw8!");
        mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("name-login-1");return req;})
            .contentType("application/json").content(login.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value(second.path("ownerId").asText()));
        login.put("password","wrongpass");
        mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("name-login-2");return req;})
            .contentType("application/json").content(login.toString())).andExpect(status().isUnauthorized());
        var third=json.readTree(mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("name-register-3");return req;})
            .contentType("application/json").content(json.createObjectNode().put("accountName",name).put("password","Firstpw8!").toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertNotEquals(first.path("uid").asText(),third.path("uid").asText());
        login.put("password","Firstpw8!");
        mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("name-login-3");return req;})
            .contentType("application/json").content(login.toString())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("name-login-4");return req;})
            .contentType("application/json").content(json.createObjectNode().put("uid",first.path("uid").asText()).put("password","Firstpw8!").toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value(first.path("ownerId").asText()));
    }
    @Test void nicknameCanMatchAnotherAccountsOldLoginAlias() throws Exception {
        var created=json.readTree(mvc.perform(post("/api/v1/auth/register").with(req->{req.setRemoteAddr("alias-register");return req;})
            .contentType("application/json").content("{\"accountName\":\"integration-owner\",\"password\":\"Otherpw8!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("alias-login");return req;})
            .contentType("application/json").content("{\"accountName\":\"integration-owner\",\"password\":\"Otherpw8!\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value(created.path("ownerId").asText()));
    }
    @Test void ledgerIsExactIdempotentAndConflictsOnConcurrentEdits() throws Exception {
        String id=id();var request=op("ledger",id,0,payload("ledger",ledger(id)));
        JsonNode first=write(request);assertEquals(first,write(request));
        var changed=op("ledger",id,0,payload("ledger",ledger(id).put("amount","13.00")));
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(changed.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.details.serverVersion").value(1));
        assertEquals("12.34",first.path("payload").path("tables").path("ledger_entries").path(0).path("amount").asText());
        var reused=request.deepCopy();
        reused.put("id",id());
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(reused.toString())).andExpect(status().isConflict());
    }
    @Test void recordsAndChangesAreIsolatedByAuthenticatedOwner() throws Exception {
        String id=id();write(op("ledger",id,0,payload("ledger",ledger(id))));
        var otherSession=json.readTree(mvc.perform(post("/api/v1/auth/register").contentType("application/json")
            .content("{\"accountName\":\"同名账户\",\"password\":\"Testpw8!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String otherToken=otherSession.path("token").asText();
        mvc.perform(get("/api/v1/records/ledger/"+id).header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sync/changes").header("Authorization","Bearer "+otherToken)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test void invalidAmountAndPartialIntakeRollbackWholeOperation() throws Exception {
        String id=id();var bad=op("ledger",id,0,payload("ledger",ledger(id).put("amount","1.234")));
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(bad.toString())).andExpect(status().isUnprocessableEntity());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cloud_records WHERE owner_id=? AND record_id=?",Integer.class,owner,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cloud_operations WHERE owner_id=? AND operation_id=?",Integer.class,owner,bad.path("operationId").asText()));
        String intakeId=id();var intake=row("intakes").put("id",intakeId).put("timezone","Asia/Shanghai").put("mealType","LUNCH");
        var empty=op("intake",intakeId,0,payload("intake",intake));
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(empty.toString())).andExpect(status().isUnprocessableEntity());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cloud_records WHERE record_id=?",Integer.class,intakeId));
    }
    @Test void foodHistoryAndIntakeSnapshotSurviveFoodEditsAndSoftDeletion() throws Exception {
        String foodId=id();var food=row("food_versions").put("foodId",foodId).put("name","酸奶").put("preparedState","PACKAGED").put("sourceType","USER_LABEL").put("kcalPer100g","66.5").put("archived",0);
        var original=payload("food",food);write(op("food",foodId,0,original));
        var newFood=original.deepCopy();newFood.with("tables").withArray("food_versions").add(food.deepCopy().put("version",2).put("kcalPer100g","77.5"));write(op("food",foodId,1,newFood));
        var overwritten=payload("food",food.deepCopy().put("kcalPer100g","99"));
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(op("food",foodId,2,overwritten).toString())).andExpect(status().isUnprocessableEntity());
        String intakeId=id();var intake=row("intakes").put("id",intakeId).put("timezone","Asia/Shanghai").put("mealType","SNACK");
        var meal=payload("intake",intake);
        meal.with("tables").withArray("intake_items").add(row("intake_items").put("id",id()).put("intakeId",intakeId).put("foodId",foodId).put("foodVersion",1).put("displayName","酸奶").put("consumedGrams","125.5").put("kcalPer100gSnapshot","66.5").put("sourceTypeSnapshot","USER_LABEL").put("nutritionStatus","COMPLETE"));
        write(op("intake",intakeId,0,meal));intake.put("deletedAtMillis",1700000000000L).put("version",2);
        var deleted=meal.deepCopy();((ObjectNode)deleted.path("tables").path("intakes").path(0)).put("deletedAtMillis",1700000000000L).put("version",2);
        var response=write(op("intake",intakeId,1,deleted));
        assertEquals("66.5",response.path("payload").path("tables").path("intake_items").path(0).path("kcalPer100gSnapshot").asText());
        assertFalse(response.path("payload").path("tables").path("intakes").path(0).path("deletedAtMillis").isNull());
    }
    @Test void planRevisionsAndActivitySegmentsAreStoredTogether() throws Exception {
        String planId=id();var plan=row("plans").put("id",planId).put("title","学习").put("timezone","Asia/Shanghai").put("scheduledStartMillis",1700000000000L).put("scheduledEndMillis",1700000060000L).put("baselineStartMillis",1700000000000L).put("baselineEndMillis",1700000060000L).put("fixed",0).put("reminderSilent",0).put("status","SCHEDULED");
        var p=payload("plan",plan);write(op("plan",planId,0,p));
        var edited=p.deepCopy();((ObjectNode)edited.path("tables").path("plans").path(0)).put("status","RESCHEDULED").put("scheduledStartMillis",1700000120000L).put("scheduledEndMillis",1700000180000L).put("version",2);
        edited.with("tables").withArray("plan_revisions").add(row("plan_revisions").put("id",id()).put("planId",planId).put("revision",1).put("scheduledStartMillis",1700000000000L).put("scheduledEndMillis",1700000060000L).put("changeReason","调整时间"));write(op("plan",planId,1,edited));
        String activityId=id();var activity=row("activities").put("id",activityId).put("title","执行学习").put("categoryName","学习").put("planId",planId).put("sessionStatus","FINISHED").put("isWork",0);
        var a=payload("activity",activity);a.with("tables").withArray("activity_segments").add(row("activity_segments").put("id",id()).put("activityId",activityId).put("startMillis",1700000120000L).put("endMillis",1700000180000L));
        assertEquals(1,write(op("activity",activityId,0,a)).path("payload").path("tables").path("activity_segments").size());
    }
    @Test void changeCursorIsOrderedAndDoesNotLoseTombstones() throws Exception {
        String id=id();var value=payload("ledger",ledger(id));write(op("ledger",id,0,value));
        ((ObjectNode)value.path("tables").path("ledger_entries").path(0)).put("deletedAtMillis",1700000000000L).put("version",2);
        JsonNode saved=write(op("ledger",id,1,value));long seq=saved.path("seq").asLong();
        mvc.perform(get("/api/v1/sync/changes?cursor="+(seq-1)+"&limit=1").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value(seq)).andExpect(jsonPath("$.items[0].payload.tables.ledger_entries[0].deletedAtMillis").value(1700000000000L));
    }
    @Test void concurrentSameVersionWritesCommitOnlyOnce() throws Exception {
        String id=id();var a=op("ledger",id,0,payload("ledger",ledger(id)));var b=op("ledger",id,0,payload("ledger",ledger(id).put("amount","14.56")));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var futures=List.of(a,b).stream().map(request->pool.submit(()->{start.await();try{service.write(owner,request);return 200;}catch(ApiError.Failure failure){return failure.status;}})).toList();
            start.countDown();var statuses=new ArrayList<Integer>();for(var future:futures)statuses.add(future.get());Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM cloud_changes WHERE owner_id=? AND record_id=?",Integer.class,owner,id));
        }finally{pool.shutdownNow();}
    }
    @Test void duplicateCategoryNamesReturnCanonicalRecordForMerge() throws Exception {
        String first=id(),second=id(),name="同名分类-"+first;
        write(op("category",first,0,payload("category",row("time_categories").put("id",first).put("name",name).put("archived",0))));
        var duplicate=op("category",second,0,payload("category",row("time_categories").put("id",second).put("name",name).put("archived",0)));
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(duplicate.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.details.id").value(first));
    }
    @Test void rejectsUnknownOrForeignFoodReferencesWithoutPartialWrite() throws Exception {
        String intakeId=id();var intake=row("intakes").put("id",intakeId).put("timezone","Asia/Shanghai").put("mealType","LUNCH");
        var meal=payload("intake",intake);meal.with("tables").withArray("intake_items").add(row("intake_items").put("id",id()).put("intakeId",intakeId).put("foodId",id()).put("foodVersion",1).put("displayName","食物").put("consumedGrams","10").put("kcalPer100gSnapshot","20").put("sourceTypeSnapshot","USER_LABEL").put("nutritionStatus","COMPLETE"));
        mvc.perform(post("/api/v1/sync/operations").header("Authorization","Bearer "+token).contentType("application/json").content(op("intake",intakeId,0,meal).toString())).andExpect(status().isUnprocessableEntity());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cloud_records WHERE record_id=?",Integer.class,intakeId));
    }
}
