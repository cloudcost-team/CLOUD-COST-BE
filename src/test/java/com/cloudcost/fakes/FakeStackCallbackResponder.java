package com.cloudcost.fakes;

import com.cloudcost.application.port.StackCallbackResponder;
import com.cloudcost.domain.account.StackCallback;
import java.util.ArrayList;
import java.util.List;

/** CloudFormation에 보낸 응답을 기록만 한다. */
public class FakeStackCallbackResponder implements StackCallbackResponder {

    public record Response(StackCallback callback, boolean success, String reason) {}

    public final List<Response> responses = new ArrayList<>();

    @Override
    public void respond(StackCallback callback, boolean success, String reason) {
        responses.add(new Response(callback, success, reason));
    }

    public Response last() {
        return responses.getLast();
    }
}
