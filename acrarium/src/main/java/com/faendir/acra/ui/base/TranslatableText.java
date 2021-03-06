/*
 * (C) Copyright 2019 Lukas Morawietz (https://github.com/F43nd1r)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.faendir.acra.ui.base;

import com.faendir.acra.util.LocalSettings;
import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.flow.server.VaadinService;

/**
 * @author lukas
 * @since 06.09.19
 */
public class TranslatableText {
    private final String id;
    private final Object[] params;

    public TranslatableText(String id, Object... params) {
        this.id = id;
        this.params = params;
    }

    public String getId() {
        return id;
    }

    public Object[] getParams() {
        return params;
    }

    public String translate() {
        return getI18NProvider().getTranslation(id, LocalSettings.getCurrentLocale(), params);
    }

    private I18NProvider getI18NProvider() {
        return VaadinService.getCurrent().getInstantiator().getI18NProvider();
    }
}
