package com.gakdo.trace.gateway.access.infra;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.gakdo.trace.gateway.access.domain.AccessRule;
import com.gakdo.trace.gateway.access.domain.AccessScope;
import com.gakdo.trace.gateway.access.domain.TokenRole;

/** access-policy.yaml(contracts x-trace-access에서 생성)을 읽는다. 형식이 틀리면 시작하지 않는다. */
public final class AccessPolicyLoader {

    private AccessPolicyLoader() {
    }

    public static List<AccessRule> load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(root instanceof Map<?, ?> map) || !(map.get("rules") instanceof List<?> items)) {
                throw new IllegalStateException(resource + ": rules 목록이 없습니다");
            }
            List<AccessRule> rules = new ArrayList<>(items.size());
            for (Object item : items) {
                rules.add(toRule((Map<?, ?>) item));
            }
            return List.copyOf(rules);
        } catch (IOException e) {
            throw new IllegalStateException("접근 규칙 파일을 읽지 못했습니다: " + resource, e);
        }
    }

    private static AccessRule toRule(Map<?, ?> item) {
        String method = String.valueOf(item.get("method"));
        String path = String.valueOf(item.get("path"));
        if (Boolean.TRUE.equals(item.get("public"))) {
            return AccessRule.publicRule(method, path);
        }
        Set<TokenRole> roles = ((List<?>) item.get("roles")).stream()
                .map(String::valueOf)
                .map(value -> TokenRole.parse(value)
                        .orElseThrow(() -> new IllegalStateException(method + " " + path + ": 모르는 역할 " + value)))
                .collect(Collectors.toUnmodifiableSet());
        AccessScope scope = AccessScope.valueOf(String.valueOf(item.get("scope")));
        boolean captureToken = Boolean.TRUE.equals(item.get("captureToken"));
        return new AccessRule(method, path, false, roles, scope, captureToken);
    }
}
