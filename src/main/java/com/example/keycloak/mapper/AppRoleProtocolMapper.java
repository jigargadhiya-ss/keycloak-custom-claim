package com.example.keycloak.mapper;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.List;

/**
 * OIDC Protocol Mapper that reads the user's "app_role" Keycloak profile
 * attribute and injects it as a JWT claim.
 *
 * Falls back to "user" when the attribute is absent or blank.
 *
 * Supports all three token targets (toggled per-mapper in Admin Console):
 *   - Access Token  (Spring Boot @PreAuthorize reads this)
 *   - ID Token
 *   - UserInfo endpoint
 */
public class AppRoleProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    public static final String PROVIDER_ID = "oidc-app-role-mapper";

    private static final String USER_ATTRIBUTE_NAME = "app_role";
    private static final String FALLBACK_VALUE = "user";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    static {
        // Adds "Token Claim Name" text field — admin types the JWT key (e.g. "app_role")
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        // Adds access/ID/userinfo toggle checkboxes — driven by implemented interfaces
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, AppRoleProtocolMapper.class);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "App Role Claim";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Maps the user's 'app_role' profile attribute to a JWT claim. "
             + "Defaults to 'user' when the attribute is absent or blank.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    /**
     * Called by AbstractOIDCProtocolMapper for each token type after checking
     * the admin-configured toggles. Uses the modern 5-argument signature
     * (non-deprecated since Keycloak 23+).
     */
    @Override
    protected void setClaim(IDToken token,
                            ProtocolMapperModel mappingModel,
                            UserSessionModel userSession,
                            KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {

        String attr = userSession.getUser().getFirstAttribute(USER_ATTRIBUTE_NAME);
        String claimValue = (attr != null && !attr.isBlank()) ? attr : FALLBACK_VALUE;

        // mapClaim reads "Token Claim Name" from mappingModel config and writes the value.
        // Supports dot-notation for nested claims (e.g. "context.app_role").
        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, claimValue);
    }
}
