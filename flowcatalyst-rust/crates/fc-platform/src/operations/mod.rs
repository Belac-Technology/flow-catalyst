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

pub mod event_type;

pub use event_type::*;
