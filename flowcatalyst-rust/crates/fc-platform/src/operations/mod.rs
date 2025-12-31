//! Operations Module
//!
//! Use cases following the Command pattern with guaranteed event emission
//! and audit logging through the UnitOfWork.
//!
//! # Architecture
//!
//! Each operation consists of:
//! - **Command**: A struct containing the input data for the operation
//! - **Event**: A domain event emitted when the operation succeeds
//! - **UseCase**: The business logic that validates, executes, and commits the operation
//!
//! # Example
//!
//! ```ignore
//! // Execute a use case
//! let command = CreateEventTypeCommand {
//!     code: "orders:fulfillment:shipment:shipped".to_string(),
//!     name: "Shipment Shipped".to_string(),
//!     description: Some("Emitted when a shipment leaves the warehouse".to_string()),
//! };
//!
//! let result = create_event_type_use_case
//!     .execute(command, execution_context)
//!     .await;
//!
//! match result {
//!     UseCaseResult::Success(event) => println!("Created: {}", event.event_type_id),
//!     UseCaseResult::Failure(error) => println!("Error: {}", error),
//! }
//! ```

pub mod client;
pub mod event_type;
pub mod principal;
pub mod role;
pub mod subscription;

// Re-export specific items to avoid ambiguity
pub use client::{
    ClientCreated, ClientUpdated, ClientActivated, ClientSuspended,
    CreateClientCommand, CreateClientUseCase,
    UpdateClientCommand, UpdateClientUseCase,
    ActivateClientCommand, ActivateClientUseCase,
    SuspendClientCommand, SuspendClientUseCase,
};

pub use event_type::{
    EventTypeCreated, EventTypeUpdated, EventTypeArchived,
    CreateEventTypeCommand, CreateEventTypeUseCase,
    UpdateEventTypeCommand, UpdateEventTypeUseCase,
    ArchiveEventTypeCommand, ArchiveEventTypeUseCase,
};

pub use principal::{
    UserCreated, UserUpdated, UserActivated, UserDeactivated, UserDeleted, RolesAssigned,
    CreateUserCommand, CreateUserUseCase,
    UpdateUserCommand, UpdateUserUseCase,
    ActivateUserCommand, ActivateUserUseCase,
    DeactivateUserCommand, DeactivateUserUseCase,
    DeleteUserCommand, DeleteUserUseCase,
};

pub use role::{
    RoleCreated, RoleUpdated, RoleDeleted,
    CreateRoleCommand, CreateRoleUseCase,
    UpdateRoleCommand, UpdateRoleUseCase,
    DeleteRoleCommand, DeleteRoleUseCase,
};

pub use subscription::{
    SubscriptionCreated, SubscriptionUpdated, SubscriptionPaused, SubscriptionResumed, SubscriptionDeleted,
    CreateSubscriptionCommand, CreateSubscriptionUseCase, EventTypeBindingInput,
    UpdateSubscriptionCommand, UpdateSubscriptionUseCase,
    PauseSubscriptionCommand, PauseSubscriptionUseCase,
    ResumeSubscriptionCommand, ResumeSubscriptionUseCase,
    DeleteSubscriptionCommand, DeleteSubscriptionUseCase,
};
