package com.runealytics;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;

@Singleton
@Getter
@Setter
public class RuneAlyticsState
{
    private boolean loggedIn;
    private boolean verified;
    private String verifiedUsername;
    private String verificationCode;

    public void reset()
    {
        loggedIn = false;
        verified = false;
        verifiedUsername = null;
        verificationCode = null;
    }
}
