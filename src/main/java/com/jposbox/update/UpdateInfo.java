package com.jposbox.update;

/** Parsed from the remote update-check JSON: {"version":"1.1.0","url":"...","notes":"..."} */
public class UpdateInfo {
    public String version;
    public String url;
    public String notes;
}
