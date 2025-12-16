<?php

return [
    /*
    |--------------------------------------------------------------------------
    | FlowCatalyst API Base URL
    |--------------------------------------------------------------------------
    |
    | The base URL for the FlowCatalyst platform API. This is typically your
    | FlowCatalyst instance URL.
    |
    */
    'base_url' => env('FLOWCATALYST_BASE_URL', 'https://api.flowcatalyst.io'),

    /*
    |--------------------------------------------------------------------------
    | OIDC Client Credentials
    |--------------------------------------------------------------------------
    |
    | These credentials are used to authenticate with the FlowCatalyst API
    | using the OAuth2 client credentials grant flow.
    |
    */
    'client_id' => env('FLOWCATALYST_CLIENT_ID'),
    'client_secret' => env('FLOWCATALYST_CLIENT_SECRET'),

    /*
    |--------------------------------------------------------------------------
    | Token URL
    |--------------------------------------------------------------------------
    |
    | The OAuth2 token endpoint. Defaults to {base_url}/oauth/token if not set.
    |
    */
    'token_url' => env('FLOWCATALYST_TOKEN_URL'),

    /*
    |--------------------------------------------------------------------------
    | Webhook Signing Secret
    |--------------------------------------------------------------------------
    |
    | The secret used to validate incoming webhook signatures from FlowCatalyst.
    | This should match the signing secret from your service account.
    |
    */
    'signing_secret' => env('FLOWCATALYST_SIGNING_SECRET'),

    /*
    |--------------------------------------------------------------------------
    | Token Caching
    |--------------------------------------------------------------------------
    |
    | Configuration for caching OAuth2 access tokens. The SDK automatically
    | refreshes tokens before they expire.
    |
    */
    'token_cache' => [
        'driver' => env('FLOWCATALYST_TOKEN_CACHE_DRIVER', 'file'),
        'key' => env('FLOWCATALYST_TOKEN_CACHE_KEY', 'flowcatalyst_access_token'),
    ],

    /*
    |--------------------------------------------------------------------------
    | HTTP Client Settings
    |--------------------------------------------------------------------------
    |
    | Configuration for the HTTP client used to communicate with the
    | FlowCatalyst API.
    |
    */
    'http' => [
        'timeout' => env('FLOWCATALYST_TIMEOUT', 30),
        'retry_attempts' => env('FLOWCATALYST_RETRY_ATTEMPTS', 3),
        'retry_delay' => env('FLOWCATALYST_RETRY_DELAY', 100), // milliseconds
    ],

    /*
    |--------------------------------------------------------------------------
    | Postbox Configuration
    |--------------------------------------------------------------------------
    |
    | The postbox allows your application to write events and dispatch jobs
    | directly to a local database table, implementing the outbox pattern.
    | FlowCatalyst will poll this table and process the messages.
    |
    */
    'postbox' => [
        /*
        |----------------------------------------------------------------------
        | Enable Postbox
        |----------------------------------------------------------------------
        |
        | Set to false to disable the postbox functionality entirely.
        |
        */
        'enabled' => env('FLOWCATALYST_POSTBOX_ENABLED', true),

        /*
        |----------------------------------------------------------------------
        | Postbox Driver
        |----------------------------------------------------------------------
        |
        | The driver to use for storing postbox messages.
        | Supported: "database" (MySQL 8.0+, PostgreSQL 12+), "mongodb"
        |
        */
        'driver' => env('FLOWCATALYST_POSTBOX_DRIVER', 'database'),

        /*
        |----------------------------------------------------------------------
        | Database Connection
        |----------------------------------------------------------------------
        |
        | The database connection to use for the postbox. Leave null to use
        | the default connection.
        |
        */
        'connection' => env('FLOWCATALYST_POSTBOX_CONNECTION'),

        /*
        |----------------------------------------------------------------------
        | Table/Collection Name
        |----------------------------------------------------------------------
        |
        | The name of the table (or MongoDB collection) for postbox messages.
        |
        */
        'table' => env('FLOWCATALYST_POSTBOX_TABLE', 'postbox_messages'),

        /*
        |----------------------------------------------------------------------
        | Tenant ID
        |----------------------------------------------------------------------
        |
        | Your FlowCatalyst tenant ID. This is required for the postbox to
        | function correctly.
        |
        */
        'tenant_id' => env('FLOWCATALYST_TENANT_ID'),

        /*
        |----------------------------------------------------------------------
        | Default Partition
        |----------------------------------------------------------------------
        |
        | The default partition ID to use when none is specified.
        |
        */
        'default_partition' => env('FLOWCATALYST_DEFAULT_PARTITION', 'default'),
    ],
];
