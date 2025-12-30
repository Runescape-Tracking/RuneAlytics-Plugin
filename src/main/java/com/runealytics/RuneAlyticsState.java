package com.runealytics;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;

@Singleton
public class RuneAlyticsState
{
    @Getter
    @Setter
    private boolean verified = false;
    private String verifiedUsername = null;

    public boolean isVerified()
    {
        return verified;
    }

    public void setVerified(boolean verified)
    {
        this.verified = verified;
    }

    public String getVerifiedUsername()
    {
        return verifiedUsername;
    }

    public void setVerifiedUsername(String username)
    {
        this.verifiedUsername = username;
    }

    public void reset()
    {
        this.verified = false;
        this.verifiedUsername = null;
    }
}
