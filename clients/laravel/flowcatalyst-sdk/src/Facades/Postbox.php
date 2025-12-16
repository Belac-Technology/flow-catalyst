<?php

declare(strict_types=1);

namespace FlowCatalyst\Facades;

use FlowCatalyst\Postbox\Contracts\PostboxDriver;
use FlowCatalyst\Postbox\DTOs\CreateDispatchJobDto;
use FlowCatalyst\Postbox\DTOs\CreateEventDto;
use FlowCatalyst\Postbox\PostboxManager;
use Illuminate\Support\Facades\Facade;

/**
 * @method static string createEvent(CreateEventDto $event)
 * @method static string createDispatchJob(CreateDispatchJobDto $job)
 * @method static string[] createEvents(array $events)
 * @method static string[] createDispatchJobs(array $jobs)
 * @method static PostboxDriver driver()
 *
 * @see PostboxManager
 */
class Postbox extends Facade
{
    /**
     * Get the registered name of the component.
     */
    protected static function getFacadeAccessor(): string
    {
        return PostboxManager::class;
    }
}
