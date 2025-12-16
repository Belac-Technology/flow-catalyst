<?php

declare(strict_types=1);

namespace FlowCatalyst;

use FlowCatalyst\Client\Auth\OidcTokenManager;
use FlowCatalyst\Client\FlowCatalystClient;
use FlowCatalyst\Postbox\Contracts\PostboxDriver;
use FlowCatalyst\Postbox\Drivers\DatabaseDriver;
use FlowCatalyst\Postbox\Drivers\MongoDriver;
use FlowCatalyst\Postbox\PostboxManager;
use Illuminate\Support\ServiceProvider;

class FlowCatalystServiceProvider extends ServiceProvider
{
    /**
     * Register any application services.
     */
    public function register(): void
    {
        $this->mergeConfigFrom(
            __DIR__ . '/../config/flowcatalyst.php',
            'flowcatalyst'
        );

        $this->registerTokenManager();
        $this->registerClient();
        $this->registerPostbox();
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        $this->publishConfig();
        $this->publishMigrations();
        $this->registerMiddleware();
    }

    /**
     * Register the OIDC token manager.
     */
    protected function registerTokenManager(): void
    {
        $this->app->singleton(OidcTokenManager::class, function ($app) {
            $config = $app['config']['flowcatalyst'];

            return new OidcTokenManager(
                baseUrl: $config['base_url'],
                clientId: $config['client_id'] ?? '',
                clientSecret: $config['client_secret'] ?? '',
                tokenUrl: $config['token_url'],
                cache: $app['cache']->driver($config['token_cache']['driver'] ?? null),
                cacheKey: $config['token_cache']['key'] ?? 'flowcatalyst_access_token'
            );
        });
    }

    /**
     * Register the FlowCatalyst client.
     */
    protected function registerClient(): void
    {
        $this->app->singleton(FlowCatalystClient::class, function ($app) {
            $config = $app['config']['flowcatalyst'];

            return new FlowCatalystClient(
                tokenManager: $app->make(OidcTokenManager::class),
                baseUrl: $config['base_url'],
                timeout: $config['http']['timeout'] ?? 30,
                retryAttempts: $config['http']['retry_attempts'] ?? 3,
                retryDelay: $config['http']['retry_delay'] ?? 100
            );
        });
    }

    /**
     * Register the postbox manager.
     */
    protected function registerPostbox(): void
    {
        // Register the driver based on configuration
        $this->app->singleton(PostboxDriver::class, function ($app) {
            $config = $app['config']['flowcatalyst']['postbox'];
            $driver = $config['driver'] ?? 'database';

            return match ($driver) {
                'mongodb' => new MongoDriver(
                    connection: $config['connection'],
                    collection: $config['table'] ?? 'postbox_messages'
                ),
                default => new DatabaseDriver(
                    connection: $config['connection'],
                    table: $config['table'] ?? 'postbox_messages'
                ),
            };
        });

        $this->app->singleton(PostboxManager::class, function ($app) {
            $config = $app['config']['flowcatalyst']['postbox'];

            return new PostboxManager(
                driver: $app->make(PostboxDriver::class),
                tenantId: (int) ($config['tenant_id'] ?? 0),
                defaultPartition: $config['default_partition'] ?? 'default'
            );
        });
    }

    /**
     * Publish the configuration file.
     */
    protected function publishConfig(): void
    {
        $this->publishes([
            __DIR__ . '/../config/flowcatalyst.php' => config_path('flowcatalyst.php'),
        ], 'flowcatalyst-config');
    }

    /**
     * Publish the database migrations.
     */
    protected function publishMigrations(): void
    {
        $this->publishes([
            __DIR__ . '/../database/migrations/' => database_path('migrations'),
        ], 'flowcatalyst-migrations');
    }

    /**
     * Register the webhook validation middleware.
     */
    protected function registerMiddleware(): void
    {
        $this->app['router']->aliasMiddleware(
            'flowcatalyst.webhook',
            \FlowCatalyst\Http\Middleware\ValidateWebhookSignature::class
        );
    }
}
