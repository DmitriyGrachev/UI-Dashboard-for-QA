package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.mapper.AiCardPayloadMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AiCardPayloadMapperTest {
    @Test
    void mapsOnlyActiveHandAndPreservesExtendedHandSyntax() {
        var mapper = new AiCardPayloadMapper();
        assertThat(mapper.expected("d_Six_u_Seven_King_u_2485A82529_bSbHbD")).isEqualTo("7K");
        assertThat(mapper.expected("u_Two_Two_Ten_Four_u_6JK4Q_bSbH")).isEqualTo("22104,");
        assertThat(mapper.expected("bN_u_22769K")).isEqualTo("22769K,");
        assertThatThrownBy(() -> mapper.expected("u_Unknown_bSbH")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.expected("d_Six_bN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.expected("u__bN")).isInstanceOf(IllegalArgumentException.class);
    }
}
