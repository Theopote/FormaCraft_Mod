package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CommunityRecipeOptionsTest {
    @Test void roundTripPreservesNameAndAxesAndRejectsOutOfRangeValues() throws Exception {
        var spec=new ComponentRecipeLibrary.Spec("窗尺寸配方",1,256,2);
        assertEquals(spec,CommunityRecipeOptions.decode(CommunityRecipeOptions.encode(spec)));
        for(var bad:new ComponentRecipeLibrary.Spec[]{new ComponentRecipeLibrary.Spec("",1,1,1),new ComponentRecipeLibrary.Spec("x\n",1,1,1),
                new ComponentRecipeLibrary.Spec("a".repeat(65),1,1,1),new ComponentRecipeLibrary.Spec("x",0,1,1),new ComponentRecipeLibrary.Spec("x",1,257,1)})
            assertThrows(IOException.class,()->CommunityRecipeOptions.encode(bad));
    }
    @Test void strictOptionsNeverAcceptClientIdentityOrCoerceValues() throws Exception {
        String json=new String(CommunityRecipeOptions.encode(new ComponentRecipeLibrary.Spec("x",1,1,1)),StandardCharsets.UTF_8);
        for(String bad:new String[]{json+"{}","/*comment*/"+json,json.replace("\"x\":1","\"x\":1,\"x\":2"),
                json.replace("\"x\":1","\"x\":\"1\""),json.replace("\"x\":1","\"x\":1.5"),
                json.replace("\"x\":1","\"x\":1,\"receiptId\":\"other\""),json.replace("\"name\":\"x\"","\"name\":\"\\uD800\""),"\ufeff"+json})
            assertThrows(IOException.class,()->CommunityRecipeOptions.decode(bad.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class,()->CommunityRecipeOptions.decode(new byte[CommunityRecipeOptions.MAX_BYTES+1]));
        assertThrows(IOException.class,()->CommunityRecipeOptions.decode(new byte[]{(byte)0xFF}));
    }
}
