// Copyright 2023 Courville Software
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.utils;

import android.content.Context;
import android.text.format.DateUtils;

import com.archos.mediacenter.video.CustomApplication;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class OpenSubtitlesApiHelper {

    private static final Logger log = LoggerFactory.getLogger(OpenSubtitlesApiHelper.class);

    // opensubtitles api helper, see. https://www.opensubtitles.com/docs/api/html/index.htm

    private static volatile OpenSubtitlesApiHelper sInstance;

    private static final String API_BASE_URL = "https://api.opensubtitles.com/api/v1/";
    private static final String USER_AGENT = "User-Agent";
    private static String USER_AGENT_VALUE = "novavideoplayer v6.2.31";
    private static final String AUTHORIZATION = "Authorization";
    private static final String API_KEY = "Api-Key";
    public static final int RESULT_CODE_OK = 200;
    public static final int RESULT_NOT_ENOUGH_PARAMETERS = 400;
    public static final int RESULT_CODE_BAD_CREDENTIALS = 401;
    public static final int RESULT_CODE_TOKEN_EXPIRED = 406;
    public static final int RESULT_CODE_QUOTA_EXCEEDED = 3;
    public static final int RESULT_CODE_BAD_API_KEY = 403;
    public static final int RESULT_CODE_INVALID_FILE_ID = 406;
    public static final int RESULT_CODE_LINK_GONE = 410;
    public static final int RESULT_CODE_TOO_MANY_REQUESTS = 429;

    private static int LAST_QUERY_RESULT = RESULT_CODE_OK;

    private static OkHttpClient httpClient;
    private static String baseUrl;
    private static String apiKey;

    private static String username = null;
    private static String password = null;
    private static String authToken = null;
    private static int allowedDownloads = 5; // default when no user is logged in
    private static String level = "";
    private static int remainingDownloads = 10;
    private static int allowedTranslations = 5;
    private static int numberDownloads = 0;
    private static String resetTimeRemaining = "";
    private static String resetTimeUTC = "";
    private static boolean vip = false;
    private static int userId = 0;
    private static boolean extInstalled = false;

    private static boolean authTokenValid = false;
    private static boolean authenticated = false;

    public OpenSubtitlesApiHelper() {
        USER_AGENT_VALUE = "novavideoplayer " + CustomApplication.getNovaShortVersion();
        log.debug("OpenSubtitlesApiHelper: USER_AGENT_VALUE = " + USER_AGENT_VALUE);
        if (log.isTraceEnabled()) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            httpClient = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .build();
        } else {
            httpClient = new OkHttpClient();
        }
        setBaseUrl(API_BASE_URL);
    }

    // get the instance
    public static OpenSubtitlesApiHelper getInstance() {
        if (sInstance == null) {
            synchronized(OpenSubtitlesApiHelper.class) {
                if (sInstance == null) sInstance = new OpenSubtitlesApiHelper();
            }
        }
        return sInstance;
    }

    public static void setAuthToken(String token) {
        authToken = token;
        authTokenValid = true;
    }

    public static void setBaseUrl(String url) {
        log.debug("setBaseUrl: " + url);
        baseUrl = url;
    }

    public static int getLastQueryResult() {
        return LAST_QUERY_RESULT;
    }

    public static int getAllowedDownloads() {
        return remainingDownloads + numberDownloads;
    }

    public static boolean isVip() {
        return vip;
    }

    public static int getRemainingDownloads() {
        return remainingDownloads;
    }

    private static void invalidToken() {
        authenticated = false;
        authTokenValid = false;
    }

    public static boolean login(String openSubtitlesApiKey, String u, String p) throws IOException {
        username = u;
        password = p;
        apiKey = openSubtitlesApiKey;
        if (u != null && u.isEmpty()) {
            log.warn("auth: no username provided, using anonymous mode");
            invalidToken();
            return false;
        }
        try {
            JSONObject authRequest = new JSONObject();
            authRequest.put("username", username);
            authRequest.put("password", password);
            MediaType JSON = MediaType.get("application/json; charset=utf-8");
            RequestBody requestBody = RequestBody.create(authRequest.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(baseUrl + "login")
                    .post(requestBody)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader(USER_AGENT, USER_AGENT_VALUE)
                    .addHeader(API_KEY, apiKey);
            Request request = requestBuilder.build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    Integer status = parseResult(jsonResponse);
                    if (status != RESULT_CODE_OK) {
                        // Handle authentication error
                        log.warn("login: jsonResponse error");
                        invalidToken();
                        return false;
                    } else {
                        // Authentication successful
                        String authToken = jsonResponse.optString("token", "");
                        if (authToken.isEmpty()) {
                            log.warn("login: no token in response");
                            invalidToken();
                            return false;
                        }
                        log.debug("login: token = " + authToken);
                        // Check if "base_url" is present in the response
                        setBaseUrl(jsonResponse.optString("base_url", API_BASE_URL));
                        // Check if "user" object is present in the response
                        if (jsonResponse.has("user")) {
                            JSONObject userObject = jsonResponse.getJSONObject("user");
                            allowedDownloads = userObject.optInt("allowed_downloads", allowedDownloads);
                            allowedTranslations = userObject.optInt("allowed_translations", allowedTranslations);
                            level = userObject.optString("level", "Sub leecher");
                            vip = userObject.optBoolean("vip", false);
                            userId = userObject.optInt("user_id", 0);
                            extInstalled = userObject.optBoolean("ext_installed", false);
                            log.debug("auth: allowed_downloads={}, level={}, vip={}", allowedDownloads, level, vip);
                        }
                        if (authToken != null) {
                            log.debug("auth: authentication successful token={}", authToken);
                            setAuthToken(authToken);
                            authenticated = true;
                            return true;
                        } else {
                            log.warn("auth: authentication unsuccessful!");
                        }
                    }
                } else {
                    log.error("login: response is not successful, error code={}, error message={}", response.code(), response.message());
                }
            }
        } catch (JSONException e) {
            log.error("login: caught JSONException", e);
        }
        invalidToken();
        return false;
    }

    public static void logout() throws IOException {
        if (authenticated) {
            try {
                Request.Builder requestBuilder = new Request.Builder()
                        .url(baseUrl + "logout")
                        .delete()
                        .addHeader(USER_AGENT, USER_AGENT_VALUE)
                        .addHeader(AUTHORIZATION, "Bearer " + authToken);
                Request request = requestBuilder.build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        Integer status = parseResult(jsonResponse);
                        if (status != RESULT_CODE_OK) {
                            log.warn("logout: error in response, code=" + LAST_QUERY_RESULT);
                        }
                    } else {
                        log.error("logout: response is not successful, error code={}, error message={}", response.code(), response.message());
                    }
                }
            } catch (JSONException e) {
                log.error("auth: caught JSONException", e);
            }
        }
        authTokenValid = false;
        authenticated = false;
        authToken = null;
    }

    private static int parseResult(JSONObject jsonResponse) throws IOException {
        Integer status = jsonResponse.optInt("status", 200);
        String message = jsonResponse.optString("message", "");
        try { // json can contain "errors" array
            JSONArray errorsArray = jsonResponse.getJSONArray("errors");
            message = message + " " + errorsArray.toString();
        } catch (JSONException e) {}
        log.debug("parseResult: status={}, message={}", status, message);
        if (status != 200) log.warn("parseResult: status={}, message={}", status, message);
        if (message.equals("invalid token")) {
            invalidToken();
            LAST_QUERY_RESULT = RESULT_CODE_TOKEN_EXPIRED;
            log.warn("parseResult: invalid token");
            return LAST_QUERY_RESULT;
        }
        switch (status) {
            case 400:
                LAST_QUERY_RESULT = RESULT_NOT_ENOUGH_PARAMETERS;
                log.warn("parseResult: not enough parameters");
                return LAST_QUERY_RESULT;
            case 401:
                LAST_QUERY_RESULT = RESULT_CODE_BAD_CREDENTIALS;
                log.warn("parseResult: bad credentials");
                return LAST_QUERY_RESULT;
            case 403:
                LAST_QUERY_RESULT = RESULT_CODE_BAD_API_KEY;
                log.warn("parseResult: bad API key");
                return LAST_QUERY_RESULT;
            case 406:
                LAST_QUERY_RESULT = RESULT_CODE_INVALID_FILE_ID;
                log.warn("parseResult: invalid file ID");
                return LAST_QUERY_RESULT;
            case 410:
                LAST_QUERY_RESULT = RESULT_CODE_LINK_GONE;
                log.warn("parseResult: invalid or expired link");
                return LAST_QUERY_RESULT;
            case 429:
                LAST_QUERY_RESULT = RESULT_CODE_TOO_MANY_REQUESTS;
                log.warn("parseResult: throttle limit reached, try later");
                return LAST_QUERY_RESULT;
        }
        remainingDownloads = jsonResponse.optInt("remaining", remainingDownloads);
        if (remainingDownloads == -1) {
            LAST_QUERY_RESULT = RESULT_CODE_QUOTA_EXCEEDED;
            log.warn("parseResult: quota exceeded");
            return LAST_QUERY_RESULT;
        }
        LAST_QUERY_RESULT = RESULT_CODE_OK;
        return LAST_QUERY_RESULT;
    }

    public static ArrayList<OpenSubtitlesSearchResult> searchSubtitle(OpenSubtitlesQueryParams fileInfo, String languages) throws IOException {
        // Note: only the first result page is queried because it is assumed that it should be enough with order_by criteria
        // input: languages is a comma separated list of languages (e.g. "en,fr")
        // output: an arrayList of OpenSubtitlesSearchResult for each subtitle found
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "subtitles").newBuilder();
        urlBuilder.addQueryParameter("languages", languages);
        urlBuilder.addQueryParameter("order_by", "from_trusted,ratings,download_count");
        if (fileInfo.getTmdbId() != null) {
            urlBuilder.addQueryParameter("tmdb_id", fileInfo.getTmdbId());
        } else {
            if (fileInfo.getImdbId() != null)
                urlBuilder.addQueryParameter("imdb_id", fileInfo.getImdbId());
            else {
                if (fileInfo.getFileName() != null) urlBuilder.addQueryParameter("query", fileInfo.getFileName());
            }
        }
        if (fileInfo.getFileHash() != null) urlBuilder.addQueryParameter("moviehash", fileInfo.getFileHash());
        if (fileInfo.isShow()) {
            if (fileInfo.getSeasonNumber() != null) urlBuilder.addQueryParameter("season_number", fileInfo.getSeasonNumber().toString());
            if (fileInfo.getEpisodeNumber() != null) urlBuilder.addQueryParameter("episode_number", fileInfo.getEpisodeNumber().toString());
        }
        String url = urlBuilder.build().toString();

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .addHeader(USER_AGENT, USER_AGENT_VALUE)
                .addHeader(API_KEY, apiKey);

        if (authenticated) requestBuilder.addHeader(AUTHORIZATION, "Bearer " + authToken);
        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                try {
                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    int result = parseResult(jsonResponse);
                    if (result != RESULT_CODE_OK) {
                        log.warn("searchSubtitle: error in response, code=" + LAST_QUERY_RESULT);
                        return null;
                    } else if (jsonResponse.has("data")) {
                        switch (result) {
                            case RESULT_CODE_OK:
                                JSONArray dataArray = jsonResponse.getJSONArray("data");
                                int numSubtitles = dataArray.length();
                                ArrayList<OpenSubtitlesSearchResult> subtitleRefs = new ArrayList<>();
                                log.debug("searchSubtitle: found {} subtitles", numSubtitles);
                                for (int i = 0; i < numSubtitles; i++) {
                                    JSONObject subtitleInfo = dataArray.getJSONObject(i);
                                    OpenSubtitlesSearchResult subtitleResult = new OpenSubtitlesSearchResult();

                                    subtitleResult.setId(subtitleInfo.optString("id", ""));
                                    if (subtitleInfo.has("attributes")) {
                                        JSONObject subtitleAttribute = subtitleInfo.getJSONObject("attributes");
                                        subtitleResult.setLanguage(subtitleAttribute.optString("language", ""));
                                        subtitleResult.setMoviehashMatch(subtitleAttribute.optBoolean("moviehash_match", false));
                                        if (subtitleAttribute.has("features")) {
                                            log.debug("searchSubtitle: it has features");
                                            JSONObject subtitleFeatures = subtitleAttribute.getJSONObject("features");
                                            subtitleResult.setRelease(subtitleFeatures.optString("release", ""));
                                            subtitleResult.setMovieName(subtitleFeatures.optString("movie_name", ""));
                                            subtitleResult.setSeasonNumber(subtitleFeatures.optInt("season_number", 0));
                                            subtitleResult.setEpisodeNumber(subtitleFeatures.optInt("episode_number", 0));
                                            subtitleResult.setFeatureType(subtitleFeatures.optString("feature_type", ""));
                                            subtitleResult.setParentTitle(subtitleFeatures.optString("parent_title", ""));
                                        }
                                        if (subtitleAttribute.has("files")) {
                                            log.debug("searchSubtitle: it has files");
                                            JSONObject subtitleFiles = subtitleAttribute.getJSONArray("files").getJSONObject(0);
                                            subtitleResult.setFileId(subtitleFiles.optString("file_id", ""));
                                            subtitleResult.setFileName(subtitleFiles.optString("file_name", ""));
                                        }
                                    }
                                    subtitleRefs.add(subtitleResult);
                                    log.debug("searchSubtitle: found " + subtitleResult);
                                    // only return one best match if hash match and single language
                                    if (subtitleResult.getMoviehashMatch() && languages.split(",").length == 1) {
                                        log.debug("searchSubtitle: hash match, focus on first match as single result");
                                        return new ArrayList<>(Arrays.asList(subtitleResult));
                                    }
                                }
                                return subtitleRefs;
                            case RESULT_CODE_TOKEN_EXPIRED:
                                // Handle invalid token error
                                if (! authTokenValid) { // one retry in case of invalid token
                                    log.warn("searchSubtitle: invalid token, retrying");
                                    login(apiKey, username, password);
                                    return searchSubtitle(fileInfo, languages);
                                } else {
                                    return null;
                                }
                            case RESULT_CODE_BAD_CREDENTIALS, RESULT_CODE_QUOTA_EXCEEDED:
                                // Handle quota error
                                return null;
                        }
                    } else return null;
                } catch (JSONException e) {
                    log.error("searchSubtitle: caught JSONException", e);
                }
            } else {
                log.error("searchSubtitle: response is not successful, error code={}, error message={}", response.code(), response.message());
            }
        }
        return null;
    }

    public static String getDownloadSubtitleLink(String file_id) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "download").newBuilder();
        String url = urlBuilder.build().toString();
        JSONObject requestData = new JSONObject();
        try {
            requestData.put("file_id", Integer.parseInt(file_id));
        } catch (JSONException e) {
            log.error("getDownloadSubtitleLink: caught JSONException", e);
        }
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        RequestBody requestBody = RequestBody.create(requestData.toString(), JSON);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader(USER_AGENT, USER_AGENT_VALUE)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader(API_KEY, apiKey);

        if (authenticated) requestBuilder.addHeader(AUTHORIZATION, "Bearer " + authToken);
        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                try {
                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    int result = parseResult(jsonResponse);
                    if (result != RESULT_CODE_OK) {
                        log.warn("getDownloadSubtitleLink: error in response, result={}", result);
                        return null;
                    } else {
                        switch (result) {
                            case RESULT_CODE_OK:
                                remainingDownloads = jsonResponse.optInt("remaining", remainingDownloads);
                                numberDownloads = jsonResponse.optInt("requests", numberDownloads);
                                resetTimeRemaining = jsonResponse.optString("reset_time", "");
                                resetTimeUTC = jsonResponse.optString("reset_time_utc", "");
                                log.debug("getDownloadSubtitleLink: remaining downloads={}, number of downloads={}", remainingDownloads, numberDownloads);
                                String subtitleLink = jsonResponse.optString("link", null);
                                log.debug("getDownloadSubtitleLink: found link {}", subtitleLink);
                                return subtitleLink;
                            case RESULT_CODE_TOKEN_EXPIRED:
                                // Handle invalid token error
                                if (! authTokenValid) { // one retry in case of invalid token
                                    log.warn("getDownloadSubtitleLink: invalid token, retrying");
                                    login(apiKey, username, password);
                                    return getDownloadSubtitleLink(file_id);
                                } else {
                                    return null;
                                }
                            case RESULT_CODE_BAD_CREDENTIALS, RESULT_CODE_QUOTA_EXCEEDED:
                                // Handle quota error
                                return null;
                        }
                    }
                } catch (JSONException e) {
                    log.error("getDownloadSubtitleLink: caught JSONException", e);
                }
            } else {
                log.error("getDownloadSubtitleLink: response is not successful, error code={}, error message={}", response.code(), response.message());
            }
        }
        return null;
    }

    public static String getTimeRemaining() {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date resetTime = dateFormat.parse(resetTimeUTC);
            Date currentTime = new Date();
            long timeDifference = resetTime.getTime() - currentTime.getTime();
            String formattedTimeRemaining = DateUtils.formatElapsedTime(timeDifference / 1000);
            return formattedTimeRemaining;
        } catch (ParseException e) {
            log.error("getTimeRemaining: caught ParseException", e);
            return "";
        }
    }
}