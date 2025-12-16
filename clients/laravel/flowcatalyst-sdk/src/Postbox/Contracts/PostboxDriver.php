<?php

declare(strict_types=1);

namespace FlowCatalyst\Postbox\Contracts;

interface PostboxDriver
{
    /**
     * Insert a single message into the postbox.
     *
     * @param array{
     *     id: string,
     *     tenant_id: int,
     *     partition_id: string,
     *     type: string,
     *     payload: string,
     *     payload_size: int,
     *     status: string,
     *     created_at: string,
     *     headers: array|null
     * } $message
     */
    public function insert(array $message): void;

    /**
     * Insert multiple messages into the postbox.
     *
     * @param array<array{
     *     id: string,
     *     tenant_id: int,
     *     partition_id: string,
     *     type: string,
     *     payload: string,
     *     payload_size: int,
     *     status: string,
     *     created_at: string,
     *     headers: array|null
     * }> $messages
     */
    public function insertBatch(array $messages): void;
}
