package cn.smartserve.business;

import cn.smartserve.infra.JsonStore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class SeedData implements ApplicationRunner {

  private final JsonStore db;

  public SeedData(JsonStore db) {
    this.db = db;
  }

  @Override
  public void run(ApplicationArguments a) {
    String[][] items = {
      {
        "lock-s1",
        "智安 S1 指纹门锁",
        "家庭安防",
        "1299",
        "一触即开，让安全与从容一起到家。",
        "指纹识别,临时密码,应急供电",
        "门锁",
        "24个月",
      },
      {
        "camera-c2",
        "守望 C2 云台摄像头",
        "家庭安防",
        "299",
        "看得清家里的每一份牵挂。",
        "2K画质,双向语音,物理遮蔽",
        "摄像头",
        "12个月",
      },
      {
        "bulb-l1",
        "晨光 L1 智能灯泡",
        "氛围照明",
        "79",
        "从晨光到晚安，一盏灯懂你的节奏。",
        "1600万色,定时唤醒,E27螺口",
        "灯泡",
        "12个月",
      },
      {
        "plug-p1",
        "轻控 P1 智能插座",
        "生活智控",
        "59",
        "给普通家电，一点聪明的改变。",
        "远程开关,电量统计,过载保护",
        "插座",
        "12个月",
      },
      {
        "hub-h1",
        "联家 H1 智能网关",
        "生活智控",
        "199",
        "连接每个设备，让全屋默契协作。",
        "Zigbee 3.0,本地场景,64台设备",
        "网关",
        "12个月",
      },
      {
        "sensor-t1",
        "知温 T1 温湿度计",
        "生活智控",
        "89",
        "舒适的家，从刚刚好的温湿度开始。",
        "实时监测,电子墨水屏,低功耗",
        "温湿度计",
        "12个月",
      },
    };
    for (String[] p : items) {
      if (db.find("product", p[0]).isPresent()) continue;
      var m = new LinkedHashMap<String, Object>();
      m.put("id", p[0]);
      m.put("name", p[1]);
      m.put("category", p[2]);
      m.put("price", new BigDecimal(p[3]));
      m.put("description", p[4]);
      m.put("features", List.of(p[5].split(",")));
      m.put("type", p[6]);
      m.put("warranty", p[7]);
      m.put("image", "/files/images/" + p[0] + ".png");
      m.put("stock", 128);
      m.put("rating", 4.9);
      db.insert("product", p[0], null, m);
    }
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    seedOrder(
      "SS20261010001",
      "lock-s1",
      "DELIVERED",
      today.minusDays(3),
      "杭州余杭 · 已签收",
      "SF1380024101001",
      "customer-001"
    );
    seedOrder(
      "SS20261010002",
      "camera-c2",
      "SHIPPED",
      today.minusDays(1),
      "杭州转运中心 · 运输中",
      "SF1380024101002",
      "customer-001"
    );
    seedOrder(
      "SS20261010003",
      "bulb-l1",
      "DELIVERED",
      today.minusDays(12),
      "杭州西湖 · 已签收",
      "SF1380024101003",
      "customer-001"
    );
    seedOrder(
      "SS20261010004",
      "hub-h1",
      "DELIVERED",
      today.minusDays(2),
      "上海浦东 · 已签收",
      "SF1380024101004",
      "customer-002"
    );
  }

  private void seedOrder(
    String id,
    String product,
    String status,
    LocalDate date,
    String logistics,
    String tracking,
    String owner
  ) {
    if (db.find("customer_order", id).isPresent()) return;
    var p = db.require("product", product);
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("userId", owner);
    m.put("productId", product);
    m.put("productName", p.get("name"));
    m.put("image", p.get("image"));
    m.put("amount", p.get("price"));
    m.put("quantity", 1);
    m.put("status", status);
    m.put("createdAt", date.minusDays(2).toString());
    m.put("deliveredAt", status.equals("DELIVERED") ? date.toString() : "");
    m.put("logistics", logistics);
    m.put("trackingNumber", tracking);
    m.put("address", "示例地址 · 联系方式已脱敏");
    db.insert("customer_order", id, owner, m);
  }
}
