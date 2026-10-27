package com.shortvideo.agent;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class CreationSettingsService {
  private final JdbcTemplate db;
  private final Json json;
  private final AuthService auth;
  private final TransactionTemplate tx;
  CreationSettingsService(JdbcTemplate db, Json json, AuthService auth, TransactionTemplate tx) {
    this.db=db; this.json=json; this.auth=auth; this.tx=tx;
  }
  Map<String,Object> get() {
    return db.query("SELECT brief_json FROM user_creation_defaults WHERE user_id=?", (rs,n)->json.object(rs.getString(1)), auth.current().id()).stream().findFirst().orElseGet(ProductionValidator::defaultBrief);
  }
  Map<String,Object> save(Map<String,Object> brief) {
    ProductionValidator.brief(brief);
    String user=auth.current().id();
    tx.executeWithoutResult(s->{
      db.query("SELECT id FROM users WHERE id=? FOR UPDATE", (rs,n)->rs.getString(1),user);
      db.update("DELETE FROM user_creation_defaults WHERE user_id=?",user);
      db.update("INSERT INTO user_creation_defaults(user_id,brief_json) VALUES(?,?)",user,json.write(brief));
    });
    return get();
  }
  Map<String,Object> reset() {
    db.update("DELETE FROM user_creation_defaults WHERE user_id=?",auth.current().id());
    return get();
  }
}
