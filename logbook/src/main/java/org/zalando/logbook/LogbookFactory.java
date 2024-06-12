package org.zalando.logbook;

import org.apiguardian.api.API;

import javax.annotation.Nullable;
import java.util.function.Predicate;

import static org.apiguardian.api.API.Status.STABLE;

@API(status = STABLE)
interface LogbookFactory {

    // workaround to resolve issue with ServiceLoader which doesn't work with OSGI
    LogbookFactory INSTANCE = new DefaultLogbookFactory();

    Logbook create(
            @Nullable final Predicate<HttpRequest> condition,
            @Nullable final CorrelationId correlationId,
            @Nullable final QueryFilter queryFilter,
            @Nullable final PathFilter pathFilter,
            @Nullable final HeaderFilter headerFilter,
            @Nullable final BodyFilter bodyFilter,
            @Nullable final RequestFilter requestFilter,
            @Nullable final ResponseFilter responseFilter,
            @Nullable final Strategy strategy,
            @Nullable final Sink sink);

}