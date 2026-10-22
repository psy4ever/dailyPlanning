package com.dayoudu.cloud;

import static com.dayoudu.cloud.ApiError.fail;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;

@Component
class RecordValidator {
    final JsonNode contract;
    RecordValidator(ObjectMapper mapper) throws Exception {
        try(var input=getClass().getResourceAsStream("/sync-contract.json")){contract=mapper.readTree(input);}
    }
    interface Lookup { JsonNode find(String kind,String id); }
    void require(boolean condition,String message){if(!condition)throw fail(422,message);}
    JsonNode root(JsonNode payload,String table){return payload.path("tables").path(table).path(0);}
    JsonNode rows(JsonNode payload,String table){return payload.path("tables").path(table);}
    void text(JsonNode row,String key,int max){require(row.path(key).isTextual()&&!row.path(key).asText().isBlank()&&row.path(key).asText().length()<=max,key+" 无效");}
    void choice(JsonNode row,String key,String... allowed){require(Set.of(allowed).contains(row.path(key).asText()),key+" 枚举无效");}
    void decimal(JsonNode row,String key,boolean optional,boolean positive,int scale,int precision){
        if(optional&&row.path(key).isNull())return;
        require(row.path(key).isTextual()&&row.path(key).asText().length()<=100,key+" 必须为十进制字符串");
        try {String input=row.path(key).asText();require(input.matches("[0-9]+(?:\\.[0-9]+)?"),key+" 格式无效");BigDecimal n=new BigDecimal(input);
            require((positive?n.signum()>0:n.signum()>=0)&&n.scale()<=scale&&n.precision()<=precision,key+" 超出范围");
        }catch(NumberFormatException e){throw fail(422,key+" 数字无效");}
    }
    void ref(Lookup lookup,String kind,JsonNode row,String key){if(!row.path(key).isNull()){SyncService.uuid(row.path(key));require(lookup.find(kind,row.path(key).asText())!=null,key+" 关联记录不存在或不属于当前账户");}}
    void validate(String kind,String id,JsonNode payload,JsonNode previous,Lookup lookup,Supplier<List<JsonNode>> others){
        JsonNode spec=contract.path("kinds").path(kind);
        require(!spec.isMissingNode(),"资源类型无效");
        require(payload.isObject()&&SyncService.fields(payload).equals(Set.of("tables")),"payload 结构无效");
        String table=spec.path("root").asText(), key=spec.path("key").asText();
        Set<String> expected=new HashSet<>(SyncService.fields(spec.path("children")));expected.add(table);
        require(payload.path("tables").isObject()&&SyncService.fields(payload.path("tables")).equals(expected),"表集合不匹配");
        for(String t:expected){
            JsonNode data=rows(payload,t), fields=contract.path("tables").path(t);
            require(data.isArray()&&data.size()<=5000,"记录数超出范围");
            Set<String> ids=new HashSet<>();
            for(JsonNode row:data){
                Set<String> supplied=SyncService.fields(row), defined=SyncService.fields(fields);
                Set<String> legacyLedger=new HashSet<>(defined);legacyLedger.removeAll(Set.of("category","note"));
                require(row.isObject()&&(supplied.equals(defined)||(t.equals("ledger_entries")&&supplied.equals(legacyLedger))),t+" 字段不匹配");
                for(String field:SyncService.fields(fields)){
                    JsonNode value=row.path(field), definition=fields.path(field);
                    if(value.isNull()||value.isMissingNode()){require(!definition.path("required").asBoolean(),field+" 不可为空");continue;}
                    if(definition.path("type").asText().equals("TEXT"))require(value.isTextual()&&value.asText().length()<=1000,field+" 文本无效");
                    else require(value.isIntegralNumber()&&value.canConvertToLong(),field+" 必须为整数");
                    if(field.endsWith("Millis"))require(value.asLong()>=0&&value.asLong()<=253402300799999L,field+" 时间无效");
                    if(field.equals("version"))require(value.asLong()>=1&&value.asLong()<=Integer.MAX_VALUE,"version 无效");
                }
                if(t.equals("food_versions")) require(ids.add(row.path("version").asText()),"食物版本重复");
                else if(row.has("id"))require(ids.add(SyncService.uuid(row.path("id"))),"记录 ID 重复");
                else if(t.equals("activity_tags")) {text(row,"name",30);require(ids.add(row.path("name").asText()),"标签重复");}
                if(t.equals(table))require(row.path(key).asText().equals(id),"资源 ID 不匹配");
                else require(row.path(spec.path("children").path(t).asText()).asText().equals(id),"子记录归属不匹配");
            }
        }
        JsonNode row=root(payload,table);
        require(rows(payload,table).size()>=1&&(kind.equals("food")||rows(payload,table).size()==1),"根记录数量无效");
        if(!spec.path("children").isEmpty())for(JsonNode other:others.get())for(String child:SyncService.fields(spec.path("children"))){
            if(!contract.path("tables").path(child).has("id"))continue;
            Set<String> used=new HashSet<>();for(JsonNode item:rows(other,child))used.add(item.path("id").asText());
            for(JsonNode item:rows(payload,child))require(!used.contains(item.path("id").asText()),"子记录 ID 已属于另一记录");
        }
        if(row.has("timezone")){try{ZoneId.of(row.path("timezone").asText());}catch(Exception e){throw fail(422,"时区无效");}}
        switch(kind){
            case "category", "project" -> {text(row,"name",kind.equals("category")?50:80);require(row.path("archived").asInt()==0||row.path("archived").asInt()==1,"停用状态无效");}
            case "ledger" -> {
                choice(row,"type","INCOME","EXPENSE");text(row,"source",100);decimal(row,"amount",false,true,2,14);
                require(row.path("note").isNull() || row.path("note").isMissingNode() || row.path("note").asText().length()<=500,"备注过长");
                if(!row.path("category").isNull() && !row.path("category").isMissingNode()) {
                    if(row.path("type").asText().equals("INCOME")) choice(row,"category","SALARY","SIDE_JOB","INVESTMENT","OTHER");
                    else choice(row,"category","FOOD","SHOPPING","TRANSPORT","HOME","DAILY","MEDICAL","FUN","STUDY","PHONE","CLOTHES","GIFT","OTHER");
                }
            }
            case "nutritionDay" -> {
                LocalDate date;
                try { date=LocalDate.parse(row.path("date").asText()); } catch(Exception e) { throw fail(422,"营养目标日期无效"); }
                String stable=UUID.nameUUIDFromBytes(("nutrition-day:"+date+":"+row.path("timezone").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                require(stable.equals(id),"营养目标 ID 与日期不匹配");
                for(String field:List.of("budgetKcal","exerciseKcal","proteinGoal","fatGoal","carbGoal")) {
                    decimal(row,field,true,false,2,9);
                    if(!row.path(field).isNull()) require(new BigDecimal(row.path(field).asText()).compareTo(new BigDecimal("100000"))<=0,"营养目标超出范围");
                }
            }
            case "plan" -> {
                text(row,"title",120);choice(row,"status","SCHEDULED","RESCHEDULED","COMPLETE","CANCELLED");
                long start=row.path("scheduledStartMillis").asLong(),end=row.path("scheduledEndMillis").asLong();
                ZoneId zone=ZoneId.of(row.path("timezone").asText());
                long nextMidnight=Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
                require(end>start&&end<=nextMidnight,"计划须在来源日内且结束晚于开始");
                require(row.path("baselineEndMillis").asLong()>row.path("baselineStartMillis").asLong(),"计划基线无效");
                require(row.path("fixed").asInt()==0||row.path("fixed").asInt()==1,"fixed 无效");
                require(row.path("reminderSilent").asInt()==0||row.path("reminderSilent").asInt()==1,"提醒状态无效");
                require(row.path("reminderMinutesBefore").isNull()||row.path("reminderMinutesBefore").asLong()>=0&&row.path("reminderMinutesBefore").asLong()<=10080,"提醒时间无效");
                for(JsonNode revision:rows(payload,"plan_revisions")){text(revision,"changeReason",1000);require(revision.path("scheduledEndMillis").asLong()>revision.path("scheduledStartMillis").asLong(),"修订时段无效");}
                if(previous!=null){
                    JsonNode old=root(previous,"plans");require(row.path("baselineStartMillis").equals(old.path("baselineStartMillis"))&&row.path("baselineEndMillis").equals(old.path("baselineEndMillis")),"首次计划基线不能改写");
                    for(JsonNode revision:rows(previous,"plan_revisions"))require(contains(rows(payload,"plan_revisions"),revision),"既有改期历史不能改写");
                }
            }
            case "food" -> {
                int v=0;
                for(JsonNode food:rows(payload,table)){
                    require(food.path("version").asInt()==++v,"食物版本须从 1 连续递增");text(food,"name",120);
                    choice(food,"preparedState","RAW","COOKED","PACKAGED","UNKNOWN");choice(food,"sourceType","USER_LABEL","USER_ESTIMATE");
                    require(food.path("archived").asInt()==0||food.path("archived").asInt()==1,"食物停用状态无效");
                    for(String n:List.of("kcalPer100g","proteinGPer100g","fatGPer100g","carbGPer100g"))decimal(food,n,!n.equals("kcalPer100g"),false,30,60);
                }
                if(previous!=null)for(JsonNode old:rows(previous,table))require(contains(rows(payload,table),old),"食物历史版本只读，请保留云端版本后重新编辑");
            }
            case "intake" -> {
                choice(row,"mealType","BREAKFAST","LUNCH","DINNER","SNACK");
                require(row.path("note").isNull()||row.path("note").asText().length()<=500,"备注过长");
                JsonNode items=rows(payload,"intake_items");require(items.size()>=1&&items.size()<=30,"一次摄入须有 1–30 项");
                for(JsonNode item:items){
                    text(item,"displayName",120);decimal(item,"consumedGrams",true,true,30,60);
                    for(String n:List.of("kcalPer100gSnapshot","proteinGPer100gSnapshot","fatGPer100gSnapshot","carbGPer100gSnapshot"))decimal(item,n,true,false,30,60);
                    choice(item,"nutritionStatus","COMPLETE","MISSING_AMOUNT","MISSING_FOOD");
                    if(!item.path("foodId").isNull()){
                        ref(lookup,"food",item,"foodId");JsonNode food=lookup.find("food",item.path("foodId").asText());
                        require(item.path("foodVersion").isIntegralNumber(),"foodVersion 无效");
                        boolean found=false;for(JsonNode f:rows(food,"food_versions"))if(f.path("version").equals(item.path("foodVersion")))found=true;
                        require(found,"引用的食物历史版本不存在");
                        require(!item.path("kcalPer100gSnapshot").isNull(),"食物能量快照缺失");
                        choice(item,"sourceTypeSnapshot","USER_LABEL","USER_ESTIMATE");
                        require(item.path("nutritionStatus").asText().equals(item.path("consumedGrams").isNull()?"MISSING_AMOUNT":"COMPLETE"),"营养完整状态与份量不一致");
                    }else {
                        require(item.path("foodVersion").isNull()&&item.path("nutritionStatus").asText().equals("MISSING_FOOD"),"未知食物状态无效");
                        text(item,"manualName",120);
                        for(String f:List.of("kcalPer100gSnapshot","sourceTypeSnapshot","proteinGPer100gSnapshot","fatGPer100gSnapshot","carbGPer100gSnapshot"))require(item.path(f).isNull(),"未知食物不能伪造营养快照");
                    }
                }
            }
            case "activity" -> {
                text(row,"title",120);text(row,"categoryName",50);choice(row,"sessionStatus","RUNNING","PAUSED","FINISHED");
                ref(lookup,"plan",row,"planId");ref(lookup,"category",row,"categoryId");ref(lookup,"project",row,"projectId");
                decimal(row,"incomeAmount",true,false,2,14);decimal(row,"expenseAmount",true,false,2,14);
                require(row.path("isWork").asInt()==0||row.path("isWork").asInt()==1,"工作状态无效");
                if(!row.path("incomeAmount").isNull()||!row.path("expenseAmount").isNull()) require(row.path("isWork").asInt()==1&&row.path("sessionStatus").asText().equals("FINISHED")&&row.path("currencyCode").asText().matches("[A-Z]{3}"),"工作现金状态或币种无效");
                require(row.path("commuteMinutes").isNull()||row.path("commuteMinutes").asLong()>=0&&row.path("commuteMinutes").asLong()<=525600,"通勤时间无效");
                List<long[]> spans=spans(payload);int open=0;for(JsonNode s:rows(payload,"activity_segments"))if(s.path("endMillis").isNull())open++;
                require(!spans.isEmpty()&&open==(row.path("sessionStatus").asText().equals("RUNNING")?1:0),"计时状态与片段不一致");
                if(row.path("deletedAtMillis").isNull()) for(JsonNode other:others.get()){
                    JsonNode otherRoot=root(other,"activities");if(!otherRoot.path("deletedAtMillis").isNull())continue;
                    require(row.path("sessionStatus").asText().equals("FINISHED")||otherRoot.path("sessionStatus").asText().equals("FINISHED"),"同一账户只能有一个未结束的计时");
                    for(long[] a:spans)for(long[] b:spans(other))require(a[1]<=b[0]||b[1]<=a[0],"活动时段与云端已有记录重叠");
                }
            }
            default -> throw fail(422,"资源类型无效");
        }
    }
    boolean contains(JsonNode rows,JsonNode target){for(JsonNode row:rows)if(row.equals(target))return true;return false;}
    List<long[]> spans(JsonNode payload){
        List<long[]> result=new ArrayList<>();
        for(JsonNode s:rows(payload,"activity_segments")){
            long start=s.path("startMillis").asLong(),end=s.path("endMillis").isNull()?Long.MAX_VALUE:s.path("endMillis").asLong();require(end>start,"计时片段无效");
            for(long[] old:result)require(end<=old[0]||old[1]<=start,"活动自身的计时片段重叠");result.add(new long[]{start,end});
        }
        return result;
    }
}
