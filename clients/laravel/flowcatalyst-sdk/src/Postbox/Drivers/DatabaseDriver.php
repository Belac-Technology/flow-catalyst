<?php

declare(strict_types=1);

namespace FlowCatalyst\Postbox\Drivers;

use FlowCatalyst\Exceptions\PostboxException;
use FlowCatalyst\Postbox\Contracts\PostboxDriver;
use Illuminate\Support\Facades\DB;

/**
 * Database driver for MySQL 8.0+ and PostgreSQL 12+.
 */
class DatabaseDriver implements PostboxDriver
{
    public function __construct(
        private readonly ?string $connection,
        private readonly string $table = 'postbox_messages'
    ) {}

    /**
     * {@inheritdoc}
     */
    public function insert(array $message): void
    {
        try {
            $this->getConnection()->table($this->table)->insert(
                $this->prepareMessage($message)
            );
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

        try {
            $prepared = array_map(
                fn(array $message) => $this->prepareMessage($message),
                $messages
            );

            $this->getConnection()->table($this->table)->insert($prepared);
        } catch (\Exception $e) {
            throw PostboxException::insertFailed($e->getMessage());
        }
    }

    /**
     * Prepare a message for database insertion.
     */
    private function prepareMessage(array $message): array
    {
        return [
            'id' => $message['id'],
            'tenant_id' => $message['tenant_id'],
            'partition_id' => $message['partition_id'],
            'type' => $message['type'],
            'payload' => $message['payload'],
            'payload_size' => $message['payload_size'],
            'status' => $message['status'],
            'created_at' => $message['created_at'],
            'headers' => isset($message['headers']) ? json_encode($message['headers']) : null,
        ];
    }

    /**
     * Get the database connection.
     */
    private function getConnection(): \Illuminate\Database\ConnectionInterface
    {
        return DB::connection($this->connection);
    }
}
