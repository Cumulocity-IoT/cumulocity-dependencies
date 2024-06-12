/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mongodb;

import com.mongodb.internal.VisibleForTesting;
import com.mongodb.lang.Nullable;
import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

import static com.mongodb.internal.VisibleForTesting.AccessModifier.PRIVATE;
import static org.bson.internal.ProvidersCodecRegistry.getFromCodecProvider;


/**
 *  * A CodecProvider for Java Records.
 *  *
 *  * <p>Original file requires java.lang.Record support - eg Java 17 or greater. That's why we override it here.</p>
 *  * Remove file when Cumulocity Core is migrated to Java 17+
 *
 * @since 4.6
 */
public class Jep395RecordCodecProvider implements CodecProvider {

    @Nullable
    private static final CodecProvider RECORD_CODEC_PROVIDER = null;

    @Override
    @Nullable
    public <T> Codec<T> get(final Class<T> clazz, final CodecRegistry registry) {
        return get(clazz, Collections.emptyList(), registry);
    }

    @Override
    @Nullable
    public <T> Codec<T> get(final Class<T> clazz, final List<Type> typeArguments, final CodecRegistry registry) {
        return RECORD_CODEC_PROVIDER != null ? getFromCodecProvider(RECORD_CODEC_PROVIDER, clazz, typeArguments, registry) : null;
    }

    /**
     * This method is not part of the public API and may be removed or changed at any time.
     *
     * @return true if records are supported
     */
    @VisibleForTesting(otherwise = PRIVATE)
    public boolean hasRecordSupport() {
        return false;
    }
}
