<?php

declare(strict_types=1);

namespace FlowCatalyst\Postbox\Drivers;

use FlowCatalyst\Exceptions\PostboxException;
use FlowCatalyst\Postbox\Contracts\PostboxDriver;

/**
 * MongoDB driver for postbox messages.
 *
 * Requires mongodb/laravel-mongodb package.
 */
class MongoDriver implements PostboxDriver
{
    public function __construct(
        private readonly ?string $connection,
        private readonly string $collection = 'postbox_messages'
    ) {}

    /**
     * {@inheritdoc}
     */
    public function insert(array $message): void
    {
        $this->ensureMongoDbAvailable();

        try {
            $this->getCollection()->insert($this->prepareMessage($message));
        } catch (\Exception $e) {
            throw PostboxException::insertFailed($e->getMessage());
        }
    }

    /**
     * {@inheritdoc}
     */
    public function insertBatch(array $messages): void
    {
        if (empty($messages)) {
            return;
        }

        $this->ensureMongoDbAvailable();

        try {
            $prepared = array_map(
                fn(array $message) => $this->prepareMessage($message),
                $messages
            );

            $this->getCollection()->insert($prepared);
        } catch (\Exception $e) {
            throw PostboxException::insertFailed($e->getMessage());
        }
    }

    /**
     * Prepare a message for MongoDB insertion.
     */
    private function prepareMessage(array $message): array
    {
        return [
            '_id' => $message['id'],
            'tenant_id' => $message['tenant_id'],
            'partition_id' => $message['partition_id'],
            'type' => $message['type'],
            'payload' => $message['payload'],
            'payload_size' => $message['payload_size'],
            'status' => $message['status'],
            'created_at' => new \MongoDB\BSON\UTCDateTime(strtotime($message['created_at']) * 1000),
            'headers' => $message['headers'] ?? null,
            'processed_at' => null,
            'retry_count' => 0,
            'error_reason' => null,
        ];
    }

    /**
     * Get the MongoDB collection.
     *
     * @return \MongoDB\Collection
     */
    private function getCollection()
    {
        $connection = $this->connection ?? config('database.default');

        return app('db')->connection($connection)->getCollection($this->collection);
    }

    /**
     * Ensure MongoDB extension and package are available.
     */
    private function ensureMongoDbAvailable(): void
    {
        if (!extension_loaded('mongodb')) {
            throw PostboxException::mongoDbNotInstalled();
        }

        if (!class_exists(\MongoDB\BSON\UTCDateTime::class)) {
            throw PostboxException::mongoDbNotInstalled();
        }
    }
}
