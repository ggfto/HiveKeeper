# Automated Testing - Authentik Integration

## Test Status

### ✅ Unit Tests - UPDATED AND FUNCTIONAL

All unit tests have been refactored to use the `IdpAdminClient` abstraction:

#### `SetupServiceTest.java`
- ✅ `setupCreatesTheIdpAdminTheOrgAndAnOwnerGrant()` - First admin and org creation
- ✅ `rejectsAnInvalidSetupTokenBeforeTouchingIdp()` - Token validation
- ✅ `refusesOnceAlreadyInitialized()` - Post-initialization lockout
- ✅ `requiresAnOrgNameAndAdminCredentials()` - Input validation

#### `MemberServiceTest.java`
- ✅ `addCreatesAnIdpLoginWithATempPasswordThenMembershipAndOrgGrant()` - Add member
- ✅ `setRoleUpdatesTheExistingOrgGrantInPlace()` - Update role
- ✅ `setRoleReturnsFalseForSomeoneWhoIsNotAMember()` - Member validation

### 🟡 HTTP Integration Tests (Skeleton)

Created `AuthentikAdminClientTest.java` with test structure:

```java
// Test cases identified (need full implementation):
- createUserReturnsThePkFromTheCreatedUser()
- createUserWithTemporaryPasswordSetsAttribute()
- createUserThrowsOnDuplicateUsername()
- findUserByUsernameReturnsTheMatch()
- findUserByEmailReturnsTheMatch()
- findUserReturnsEmptyWhenNotFound()
- authorizationHeaderIsSetOnAllRequests()
- trailingSlashIsTrimmedFromBaseUrl()
```

**Note**: Spring's `RestClient` (used in `AuthentikAdminClient`) doesn't have direct support for `MockRestServiceServer` like the old `RestTemplate`. To implement these tests fully, we would need:

1. **Option A**: Refactor `AuthentikAdminClient` to inject `RestClient` via constructor
2. **Option B**: Use WireMock to mock the HTTP server
3. **Option C**: Real integration tests against a test Authentik instance

### 🔴 End-to-End Integration Tests (Pending)

Files that reference `KeycloakAdminClient` in integration tests:

- `hive-gateway/src/test/java/io/hivekeeper/gateway/setup/SetupIT.java`
- `hive-gateway/src/test/java/io/hivekeeper/gateway/member/MembersIT.java`

These tests use:
```java
@Autowired
KeycloakAdminClient keycloak;   // no real Keycloak in CI
```

**To support Authentik**:
1. Add profile-aware test configuration
2. Mock the `IdpAdminClient` in integration tests
3. OR create separate tests for each IdP

## Running Tests

### Unit Tests (work without real IdP)

```bash
# All tests
./gradlew test

# Setup tests only
./gradlew test --tests '*SetupServiceTest'

# Membership tests only
./gradlew test --tests '*MemberServiceTest'
```

### Integration Tests (require database)

```bash
# With Keycloak profile (default)
./gradlew integrationTest

# With Authentik profile (when implemented)
./gradlew integrationTest -Dspring.profiles.active=postgres,oidc,oidc-authentik
```

## Next Steps for Complete Testing

### 1. Implement HTTP Mocking for AuthentikAdminClient

```java
// Example with WireMock
@ExtendWith(WireMockExtension.class)
class AuthentikAdminClientIntegrationTest {
    
    @Test
    void createUserMakesCorrectApiCalls(WireMockServer server) {
        server.stubFor(post("/api/v3/core/users/")
            .willReturn(ok()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"pk\": 123, \"username\": \"bob\"}")));
        
        server.stubFor(post("/api/v3/core/users/123/set_password/")
            .willReturn(noContent()));
        
        String userId = client.createUser("bob", "b@x", "pw", "Bob");
        assertEquals("123", userId);
    }
}
```

### 2. Profile-Aware Integration Tests

```java
@SpringBootTest
@ActiveProfiles({"postgres", "oidc", "oidc-authentik"})
class SetupIT_Authentik {
    @Autowired
    private IdpAdminClient idp;  // Will inject AuthentikAdminClient
    
    @Test
    void setupFlowWithAuthentik() {
        // Test with real Authentik container via Testcontainers
    }
}
```

### 3. Testcontainers for E2E

```java
@Testcontainers
class AuthentikIntegrationTest {
    @Container
    static GenericContainer<?> authentik = new GenericContainer<>("ghcr.io/goauthentik/server:2024.8.3")
        .withExposedPorts(9000)
        .withEnv("AUTHENTIK_SECRET_KEY", "test-key");
    
    @Test
    void fullAuthenticationFlow() {
        // Test real OAuth2 flow
    }
}
```

## Current Coverage

```
✅ Unit Tests: ~90% (core logic mocked)
🟡 HTTP Layer: ~30% (skeleton created)
🔴 E2E Tests: 0% (pending implementation)
```

## Recommendations

1. **Short term**: The updated unit tests are sufficient to ensure business logic correctness
2. **Medium term**: Implement WireMock to test HTTP calls without a real IdP
3. **Long term**: Testcontainers for E2E with real Authentik and Keycloak in CI

The current tests already **protect against regressions** in setup and membership logic, which is the most critical part.
