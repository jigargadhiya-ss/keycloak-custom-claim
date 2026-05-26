# Keycloak App Role Protocol Mapper

A custom Keycloak SPI (Protocol Mapper) that injects an `app_role` claim into OIDC tokens.

**Behaviour:**
- Reads the `app_role` attribute from the user's Keycloak profile
- If the attribute is absent or blank → falls back to `"user"`
- Claim is placed in the access token (and optionally ID token / userinfo)

**Keycloak version:** 22+ (Quarkus-based)

---

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker with a running Keycloak container

---

## Step 1 — Match your Keycloak version

Open `pom.xml` and set `<keycloak.version>` to match the **exact** version tag of your
running Docker image. A mismatch causes `NoSuchMethodError` or `ClassCastException` at runtime.

```xml
<keycloak.version>26.0.6</keycloak.version>
```

---

## Step 2 — Build

```bash
mvn clean package
```

Output: `target/keycloak-app-role-mapper-1.0.0.jar`

---

## Step 3 — Deploy to Running Docker Container

```bash
# Find your container name or ID
docker ps | grep keycloak

# Copy JAR into Keycloak's providers directory
docker cp target/keycloak-app-role-mapper-1.0.0.jar <CONTAINER>:/opt/keycloak/providers/

# Quarkus augmentation — MANDATORY after adding any new provider JAR.
# This bakes the provider into the fast-start image. Takes ~30–90 seconds.
docker exec -it <CONTAINER> /opt/keycloak/bin/kc.sh build

# Restart so the new image is used
docker restart <CONTAINER>

# Confirm registration in startup logs
docker logs <CONTAINER> | grep -i "oidc-app-role"
```

### Docker Compose variant

```bash
docker cp target/keycloak-app-role-mapper-1.0.0.jar keycloak:/opt/keycloak/providers/
docker exec -it keycloak /opt/keycloak/bin/kc.sh build
docker-compose restart keycloak
```

---

## Step 4 — Deploy via Dockerfile (CI/CD / immutable image)

```dockerfile
FROM quay.io/keycloak/keycloak:26.0.6 AS builder

COPY target/keycloak-app-role-mapper-1.0.0.jar /opt/keycloak/providers/

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.0.6

COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
```

Build and run:

```bash
mvn clean package
docker build -t my-keycloak-with-app-role .
docker run -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  my-keycloak-with-app-role start --optimized \
  --hostname-strict=false --http-enabled=true
```

---

## Step 5 — Configure the User Profile Attribute

Keycloak 22+ requires attributes to be declared in the User Profile before they
are readable inside mappers.

```
Admin Console
  → Realm Settings
  → User Profile tab
  → Add attribute
      Name:        app_role
      Display name: App Role
      (Leave validation / permissions at defaults for now)
  → Save
```

---

## Step 6 — Add the Mapper to a Client Scope

**Option A — Dedicated Client Scope (recommended, reusable across clients):**

```
Admin Console
  → Client Scopes
  → Create client scope
      Name:     app-role
      Protocol: openid-connect
      Type:     Default
  → Mappers tab
  → Add mapper → By configuration
  → Choose "App Role Claim"  (category: "Token mapper")
  → Configure:
      Name:                 app-role-mapper
      Token Claim Name:     app_role
      Add to access token:  ON
      Add to ID token:      ON   (optional)
      Add to userinfo:      ON   (optional)
  → Save
```

Then assign the scope to your client:

```
Admin Console
  → Clients → <Your Client>
  → Client Scopes tab
  → Add client scope → select "app-role" → Default
```

**Option B — Directly on a client:**

```
Admin Console
  → Clients → <Your Client>
  → Client Scopes tab
  → (Dedicated) → Configure a new mapper directly
```

---

## Step 7 — Set the Attribute on a Test User

```
Admin Console
  → Users → <your test user>
  → Attributes tab
  → Add attribute
      Key:   app_role
      Value: admin          ← or any role value your backend expects
  → Save
```

---

## Step 8 — Verify

### A. Token endpoint (curl)

```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/<REALM>/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=<CLIENT_ID>" \
  -d "client_secret=<CLIENT_SECRET>" \
  -d "username=<USERNAME>" \
  -d "password=<PASSWORD>" \
  | jq -r '.access_token')

# Decode JWT payload and check the claim
echo $TOKEN | cut -d. -f2 \
  | awk '{n=length($0)%4; if(n) s=substr("====",1,4-n); print $0 s}' \
  | base64 -d | jq .app_role

# Expected:
#   "admin"  — when app_role attribute is set on the user
#   "user"   — when the attribute is absent or blank
```

### B. Admin Console token preview (no real token needed)

```
Admin Console
  → Clients → <Your Client>
  → Client Scopes tab
  → Evaluate sub-tab
  → Enter a username → Generate token
  → Check "Generated access token" payload for "app_role" key
```

---

## Spring Boot Integration

The claim name in the JWT is `app_role`. In Spring Security, roles extracted from
a custom claim need a converter. Add the following configuration:

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("app_role");  // claim name in the token
        converter.setAuthorityPrefix("ROLE_");           // @PreAuthorize expects ROLE_ prefix

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(converter);
        return jwtConverter;
    }
}
```

Then on your controllers:

```java
@GetMapping("/admin-only")
@PreAuthorize("hasRole('admin')")   // matches ROLE_admin from token claim "app_role"
public ResponseEntity<String> adminEndpoint() {
    return ResponseEntity.ok("Hello admin");
}
```

---

## Common Pitfalls

| Pitfall | Symptom | Fix |
|---|---|---|
| Skipping `kc.sh build` | Mapper absent from "By configuration" list | Always run build + restart after adding the JAR |
| Fat JAR with Keycloak classes | `ClassCastException` on startup | All deps must be `provided` scope — never use shade/spring-boot plugin |
| Wrong SPI filename | Provider not discovered | File name = `org.keycloak.protocol.ProtocolMapper` (no extension) |
| Version mismatch | Compile errors / `NoSuchMethodError` at runtime | `keycloak.version` in pom.xml must match the Docker image tag exactly |
| Attribute not declared in User Profile | `getFirstAttribute()` always returns `null` | Declare `app_role` in Realm Settings → User Profile |
| Missing `ROLE_` prefix in Spring Security | `@PreAuthorize("hasRole('admin')")` never passes | Set `setAuthorityPrefix("ROLE_")` in `JwtGrantedAuthoritiesConverter` |
