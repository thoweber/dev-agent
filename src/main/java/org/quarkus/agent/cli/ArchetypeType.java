package org.quarkus.agent.cli;

import java.util.Arrays;

/**
 * Supported archetype project profiles for dev-agent init.
 */
public enum ArchetypeType {
    REST_PANACHE("rest-panache"),
    REACTIVE_MESSAGING("reactive-messaging"),
    FULLSTACK_ANGULAR("fullstack-angular"),
    MODULITH("modulith");

    private final String id;

    ArchetypeType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static ArchetypeType fromString(String value) {
        if (value == null || value.isBlank()) {
            return REST_PANACHE;
        }
        for (ArchetypeType type : values()) {
            if (type.id.equalsIgnoreCase(value.trim()) || type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown archetype profile: '" + value + "'. Valid options: " 
            + Arrays.toString(Arrays.stream(values()).map(ArchetypeType::getId).toArray()));
    }
}
