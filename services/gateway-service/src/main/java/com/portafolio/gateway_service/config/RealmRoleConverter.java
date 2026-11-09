package com.portafolio.gateway_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class RealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities =
                new ArrayList<>(scopes.convert(jwt));

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object value : roles) {
                if (value instanceof String role && !role.isBlank()) {
                    authorities.add(
                            new SimpleGrantedAuthority("ROLE_" + role)
                    );
                }
            }
        }

        return authorities;
    }
}
