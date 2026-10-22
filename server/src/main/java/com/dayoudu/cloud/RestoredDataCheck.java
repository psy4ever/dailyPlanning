package com.dayoudu.cloud;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Activated only on an isolated restore instance; validates every restored aggregate. */
@Component
@ConditionalOnProperty(name="daily.verify-restored",havingValue="true")
class RestoredDataCheck implements ApplicationRunner {
    final SyncService service;
    RestoredDataCheck(SyncService service){this.service=service;}
    public void run(ApplicationArguments args){
        var rows=service.db.queryForList("SELECT owner_id,kind,record_id,payload FROM cloud_records");
        for(var row:rows){
            String owner=(String)row.get("owner_id"),kind=(String)row.get("kind"),id=(String)row.get("record_id");
            service.validator.validate(kind,id,service.parse((String)row.get("payload")),null,
                (k,key)->{var record=service.find(owner,k,key);return record==null?null:record.path("payload");},
                ()->service.db.queryForList("SELECT payload FROM cloud_records WHERE owner_id=? AND kind=? AND record_id<>?",String.class,owner,kind,id).stream().map(service::parse).toList());
        }
        System.out.println("Restored aggregate validation passed: "+rows.size()+" records.");
    }
}
