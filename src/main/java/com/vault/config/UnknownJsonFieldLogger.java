package com.vault.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * เขียน warning ทุกครั้งที่ request ส่ง field ที่ DTO ไม่รู้จักมา
 *
 * Spring Boot ตั้ง Jackson ให้ทิ้ง field แปลกปลอมเงียบ ๆ — API ยังตอบ 201 ปกติ
 * ฝั่งที่เรียกจึงไม่มีทางรู้ว่าส่งชื่อผิด และเราก็ไม่มีหลักฐานว่าเขาส่งอะไรมา
 * (เคยเสียเวลาทั้งวันไล่ว่าแอปส่ง QR token มาหรือยัง เพราะไม่มีร่องรอยเหลือที่ไหนเลย)
 *
 * ไม่ทำให้ request ล้ม — ของเดิมที่ส่ง field เกินมาต้องใช้ได้ต่อ แค่ทิ้งร่องรอยไว้ใน log
 * log แค่ชื่อ field ไม่ log ค่า — ค่าอาจเป็น PIN หรือ token
 */
@Configuration
public class UnknownJsonFieldLogger {

    private static final Logger log = LoggerFactory.getLogger(UnknownJsonFieldLogger.class);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer logUnknownJsonFields() {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new Handler()));
    }

    static final class Handler extends DeserializationProblemHandler {
        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                             JsonDeserializer<?> deserializer,
                                             Object beanOrClass, String propertyName) throws IOException {
            String target = beanOrClass instanceof Class<?> c ? c.getSimpleName()
                          : beanOrClass != null ? beanOrClass.getClass().getSimpleName()
                          : "?";
            log.warn("[API] Unknown field '{}' in {} — ignored", propertyName, target);
            p.skipChildren();
            return true;
        }
    }
}
