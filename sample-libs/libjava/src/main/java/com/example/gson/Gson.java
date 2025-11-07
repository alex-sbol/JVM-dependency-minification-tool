package com.example.gson;

import com.example.annotations.MyAnno;

@MyAnno(String.class)
public class Gson {
    public static GsonBuilder newBuilder() {
        return new GsonBuilder();
    }

    public <T> T fromJson(String json, Class<T> type) {
        return null; // bodies are ignored by the minifier
    }

    @Deprecated
    public void deprecatedMethod() {}
}
