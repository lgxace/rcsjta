/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.gsma.rcs.core.ims.protocol.sdp;

import java.util.Vector;

/**
 * Session description
 * 
 * @author jexa7410
 */
public class SessionDescription {
    public Vector<TimeDescription> timeDescriptions;

    public Vector<MediaAttribute> sessionAttributes;

    public boolean connectionIncluded;

    public String version;

    public String origin;

    public String sessionName;

    public String sessionInfo;

    public String uri;

    public String email;

    public String phone;

    public String connectionInfo;

    public String bandwidthInfo;

    public String timezoneAdjustment;

    public String encryptionKey;

    public MediaAttribute getSessionAttribute(String name) {
        MediaAttribute attribute = null;
        if (sessionAttributes != null) {
            for (int i = 0; i < sessionAttributes.size(); i++) {
                MediaAttribute entry = (MediaAttribute) sessionAttributes.elementAt(i);
                if (entry.getName().equals(name)) {
                    attribute = entry;
                    break;
                }
            }
        }
        return attribute;
    }
}
