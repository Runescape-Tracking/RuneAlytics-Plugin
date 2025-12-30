package com.runealytics;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;

@Singleton
public class RuneAlyticsState
{
    @Getter
    @Setter
    private boolean verified;
}
