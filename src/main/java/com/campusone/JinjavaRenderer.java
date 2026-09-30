package com.campusone;

import com.hubspot.jinjava.Jinjava;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class JinjavaRenderer {
    private final Jinjava jinjava = new Jinjava();

    public String render(String templateName, Map<String, Object> model, Map<String, Object> session, List<String> flashes) {
        Map<String, Object> ctx = new HashMap<>();
        if (model != null) ctx.putAll(model);
        ctx.put("session", session == null ? new HashMap<>() : session);
        ctx.put("flash_messages", flashes == null ? List.of() : flashes);
        // Cart counts are section-specific. Pages that need a count provide it explicitly.
        if (!ctx.containsKey("cart_count")) {
            Object storeCart = session == null ? null : session.get("cart");
            int storeCount = storeCart instanceof Collection ? ((Collection<?>) storeCart).size() : 0;
            ctx.put("cart_count", storeCount);
        }
        Object name = ctx.get("name");
        ctx.put("first_name", name == null ? "Student" : name.toString().trim().split("\\s+")[0]);
        return jinjava.render(load(templateName), ctx);
    }

    private String load(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("templates/" + name);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Template not found: " + name, e);
        }
    }
}
