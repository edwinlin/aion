// == Rpc.java == 
package org.aion.api.server.rpc2.autogen;
import org.aion.api.server.rpc2.autogen.pod.*;
import org.aion.api.server.rpc2.autogen.errors.*;

/******************************************************************************
*
* AUTO-GENERATED SOURCE FILE.  DO NOT EDIT MANUALLY -- YOUR CHANGES WILL
* BE WIPED OUT WHEN THIS FILE GETS RE-GENERATED OR UPDATED.
*
*****************************************************************************/
public interface Rpc {

    byte[] getseed(
    )
    ;

    byte[] submitseed(
        byte[] var0, 
        byte[] var1
    )
    ;

    boolean submitsignature(
        byte[] var0, 
        byte[] var1
    )
    ;

    Transaction eth_getTransactionByHash2(
        byte[] var0
    )
    ;

    byte[] eth_call2(
        CallRequest var0
    )
    ;

    byte[] eth_sendTransaction2(
        CallRequest var0
    )
    throws UnauthorizedRpcException
    ;

}
