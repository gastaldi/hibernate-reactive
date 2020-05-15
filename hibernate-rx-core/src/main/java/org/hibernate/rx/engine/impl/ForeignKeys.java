/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.rx.engine.impl;

import org.hibernate.EntityMode;
import org.hibernate.HibernateException;
import org.hibernate.TransientObjectException;
import org.hibernate.bytecode.enhance.spi.LazyPropertyInitializer;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SelfDirtinessTracker;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.rx.persister.entity.impl.RxEntityPersister;
import org.hibernate.rx.util.impl.RxUtil;
import org.hibernate.type.CompositeType;
import org.hibernate.type.EntityType;
import org.hibernate.type.Type;

import java.io.Serializable;
import java.util.concurrent.CompletionStage;

/**
 * Algorithms related to foreign key constraint transparency
 *
 * @author Gavin King
 */
public final class ForeignKeys {

	/**
	 * Delegate for handling nullifying ("null"ing-out) non-cascaded associations
	 */
	public static class Nullifier {
		private final boolean isDelete;
		private final boolean isEarlyInsert;
		private final SessionImplementor session;
		private final Object self;
		private final EntityPersister persister;

		/**
		 * Constructs a Nullifier
		 *
		 * @param self The entity
		 * @param isDelete Are we in the middle of a delete action?
		 * @param isEarlyInsert Is this an early insert (INSERT generated id strategy)?
		 * @param session The session
		 * @param persister The EntityPersister for {@code self}
		 */
		public Nullifier(
				final Object self,
				final boolean isDelete,
				final boolean isEarlyInsert,
				final SessionImplementor session,
				final EntityPersister persister) {
			this.isDelete = isDelete;
			this.isEarlyInsert = isEarlyInsert;
			this.session = session;
			this.persister = persister;
			this.self = self;
		}

		/**
		 * Nullify all references to entities that have not yet been inserted in the database, where the foreign key
		 * points toward that entity.
		 *
		 * @param values The entity attribute values
		 */
		public CompletionStage<Void> nullifyTransientReferences(final Object[] values) {
			final String[] propertyNames = persister.getPropertyNames();
			final Type[] types = persister.getPropertyTypes();
			CompletionStage<Void> stage = RxUtil.nullFuture();
			for ( int i = 0; i < types.length; i++ ) {
				int ii = i;
				stage = stage.thenCompose( v -> nullifyTransientReferences( values[ii], propertyNames[ii], types[ii] )
						.thenAccept( val -> values[ii] = val ) );
			}
			return stage;
		}

		/**
		 * Return null if the argument is an "unsaved" entity (ie. one with no existing database row), or the
		 * input argument otherwise.  This is how Hibernate avoids foreign key constraint violations.
		 *
		 * @param value An entity attribute value
		 * @param propertyName An entity attribute name
		 * @param type An entity attribute type
		 *
		 * @return {@code null} if the argument is an unsaved entity; otherwise return the argument.
		 */
		private CompletionStage<Object> nullifyTransientReferences(final Object value, final String propertyName, final Type type) {
			final CompletionStage<Object> result;
			if ( value == null ) {
				result = RxUtil.nullFuture();
			}
			else if ( type.isEntityType() ) {
				final EntityType entityType = (EntityType) type;
				if ( entityType.isOneToOne() ) {
					result = RxUtil.completedFuture( value );
				}
				else {
					// If value is lazy, it may need to be initialized to
					// determine if the value is nullifiable.
					result = initializeIfNecessary( value, propertyName, entityType )
							.thenCompose( possiblyInitializedValue -> {
						if ( possiblyInitializedValue == null ) {
							// The uninitialized value was initialized to null
							return RxUtil.nullFuture();
						}
						else {
							// If the value is not nullifiable, make sure that the
							// possibly initialized value is returned.
							return isNullifiable( entityType.getAssociatedEntityName(), possiblyInitializedValue )
									.thenApply( trans -> trans ? null : possiblyInitializedValue );
						}
						//TODO: do we need to worry about SelfDirtinessTracker like below???
					} );
				}
			}
			else if ( type.isAnyType() ) {
				result = isNullifiable( null, value )
						.thenApply( trans -> trans  ? null : value );
			}
			else if ( type.isComponentType() ) {
				final CompositeType actype = (CompositeType) type;
				final Object[] subvalues = actype.getPropertyValues( value, session );
				final Type[] subtypes = actype.getSubtypes();
				final String[] subPropertyNames = actype.getPropertyNames();
//				boolean substitute = false;
				CompletionStage<Void> nullified = RxUtil.nullFuture();
				for ( int i = 0; i < subvalues.length; i++ ) {
					int ii = i;
					nullified = nullified.thenCompose(
							v-> nullifyTransientReferences(
									subvalues[ii],
									StringHelper.qualify( propertyName, subPropertyNames[ii] ),
									subtypes[ii]
							) )
							.thenAccept( replacement -> {
								if ( replacement != subvalues[ii] ) {
//									substitute = true;
									subvalues[ii] = replacement;
								}
							} );
				}
//				if ( substitute ) {
					// todo : need to account for entity mode on the CompositeType interface :(
				result = nullified.thenAccept( v -> actype.setPropertyValues( value, subvalues, EntityMode.POJO ) )
						.thenApply( v -> value );
//				}
			}
			else {
				result = RxUtil.completedFuture( value );
			}

			return result.thenApply( returnedValue -> {
				trackDirt( value, propertyName, returnedValue );
				return returnedValue;
			} );
		}

		private void trackDirt(Object value, String propertyName, Object returnedValue) {
			// value != returnedValue if either:
			// 1) returnedValue was nullified (set to null);
			// or 2) returnedValue was initialized, but not nullified.
			// When bytecode-enhancement is used for dirty-checking, the change should
			// only be tracked when returnedValue was nullified (1)).
			if ( value != returnedValue && returnedValue == null
					&& SelfDirtinessTracker.class.isInstance( self ) ) {
				( (SelfDirtinessTracker) self ).$$_hibernate_trackChange( propertyName );
			}
		}

		private CompletionStage<Object> initializeIfNecessary(
				final Object value,
				final String propertyName,
				final Type type) {
			if ( isDelete &&
					value == LazyPropertyInitializer.UNFETCHED_PROPERTY &&
					type.isEntityType() &&
					!session.getPersistenceContextInternal().isNullifiableEntityKeysEmpty() ) {
				// IMPLEMENTATION NOTE: If cascade-remove was mapped for the attribute,
				// then value should have been initialized previously, when the remove operation was
				// cascaded to the property (because CascadingAction.DELETE.performOnLazyProperty()
				// returns true). This particular situation can only arise when cascade-remove is not
				// mapped for the association.

				// There is at least one nullifiable entity. We don't know if the lazy
				// associated entity is one of the nullifiable entities. If it is, and
				// the property is not nullified, then a constraint violation will result.
				// The only way to find out if the associated entity is nullifiable is
				// to initialize it.
				// TODO: there may be ways to fine-tune when initialization is necessary
				//       (e.g., only initialize when the associated entity type is a
				//       superclass or the same as the entity type of a nullifiable entity).
				//       It is unclear if a more complicated check would impact performance
				//       more than just initializing the associated entity.
				throw new UnsupportedOperationException("lazy property initialization not supported");
//				return ( (LazyPropertyInitializer) persister ).initializeLazyProperty( propertyName, self, session );
			}
			else {
				return RxUtil.completedFuture( value );
			}
		}

		/**
		 * Determine if the object already exists in the database,
		 * using a "best guess"
		 *
		 * @param entityName The name of the entity
		 * @param object The entity instance
		 */
		private CompletionStage<Boolean> isNullifiable(final String entityName, Object object)
				throws HibernateException {
			if ( object == LazyPropertyInitializer.UNFETCHED_PROPERTY ) {
				// this is the best we can do...
				return RxUtil.falseFuture();
			}

			if ( object instanceof HibernateProxy ) {
				// if its an uninitialized proxy it can't be transient
				final LazyInitializer li = ( (HibernateProxy) object ).getHibernateLazyInitializer();
				if ( li.getImplementation( session ) == null ) {
					return RxUtil.falseFuture();
					// ie. we never have to null out a reference to
					// an uninitialized proxy
				}
				else {
					//unwrap it
					object = li.getImplementation( session );
				}
			}

			// if it was a reference to self, don't need to nullify
			// unless we are using native id generation, in which
			// case we definitely need to nullify
			if ( object == self ) {
				return RxUtil.completedFuture( isEarlyInsert
						|| isDelete && session.getFactory().getDialect().hasSelfReferentialForeignKeyBug() );
			}

			// See if the entity is already bound to this session, if not look at the
			// entity identifier and assume that the entity is persistent if the
			// id is not "unsaved" (that is, we rely on foreign keys to keep
			// database integrity)

			final EntityEntry entityEntry = session.getPersistenceContextInternal().getEntry( object );
			if ( entityEntry == null ) {
				return isTransient( entityName, object, null, session );
			}
			else {
				return RxUtil.completedFuture( entityEntry.isNullifiable( isEarlyInsert, session ) );
			}
		}
	}

	/**
	 * Is this instance persistent or detached?
	 * <p/>
	 * If <tt>assumed</tt> is non-null, don't hit the database to make the determination, instead assume that
	 * value; the client code must be prepared to "recover" in the case that this assumed result is incorrect.
	 *
	 * @param entityName The name of the entity
	 * @param entity The entity instance
	 * @param assumed The assumed return value, if avoiding database hit is desired
	 * @param session The session
	 *
	 * @return {@code true} if the given entity is not transient (meaning it is either detached/persistent)
	 */
	@SuppressWarnings("SimplifiableIfStatement")
	public static CompletionStage<Boolean> isNotTransient(String entityName, Object entity, Boolean assumed, SessionImplementor session) {
		if ( entity instanceof HibernateProxy ) {
			return RxUtil.trueFuture();
		}

		if ( session.getPersistenceContextInternal().isEntryFor( entity ) ) {
			return RxUtil.trueFuture();
		}

		// todo : shouldnt assumed be revered here?

		return isTransient( entityName, entity, assumed, session )
				.thenApply( trans -> !trans );
	}

	/**
	 * Is this instance, which we know is not persistent, actually transient?
	 * <p/>
	 * If <tt>assumed</tt> is non-null, don't hit the database to make the determination, instead assume that
	 * value; the client code must be prepared to "recover" in the case that this assumed result is incorrect.
	 *
	 * @param entityName The name of the entity
	 * @param entity The entity instance
	 * @param assumed The assumed return value, if avoiding database hit is desired
	 * @param session The session
	 *
	 * @return {@code true} if the given entity is transient (unsaved)
	 */
	public static CompletionStage<Boolean> isTransient(String entityName, Object entity, Boolean assumed, SessionImplementor session) {
		if ( entity == LazyPropertyInitializer.UNFETCHED_PROPERTY ) {
			// an unfetched association can only point to
			// an entity that already exists in the db
			return RxUtil.falseFuture();
		}

		// let the interceptor inspect the instance to decide
		Boolean isUnsaved = session.getInterceptor().isTransient( entity );
		if ( isUnsaved != null ) {
			return RxUtil.completedFuture( isUnsaved );
		}

		// let the persister inspect the instance to decide
		final EntityPersister persister = session.getEntityPersister( entityName, entity );
		isUnsaved = persister.isTransient( entity, session );
		if ( isUnsaved != null ) {
			return RxUtil.completedFuture( isUnsaved );
		}

		// we use the assumed value, if there is one, to avoid hitting
		// the database
		if ( assumed != null ) {
			return RxUtil.completedFuture( assumed );
		}

		return ((RxEntityPersister) persister).rxIsTransient( entity, session );

		//TODO: reactive snapshot fetching!!!!

		// hit the database, after checking the session cache for a snapshot
//		final Object[] snapshot = session.getPersistenceContextInternal().getDatabaseSnapshot(
//				persister.getIdentifier( entity, session ),
//				persister
//		);
//		return RxUtil.completedFuture(snapshot == null);

	}

	/**
	 * Return the identifier of the persistent or transient object, or throw
	 * an exception if the instance is "unsaved"
	 * <p/>
	 * Used by OneToOneType and ManyToOneType to determine what id value should
	 * be used for an object that may or may not be associated with the session.
	 * This does a "best guess" using any/all info available to use (not just the
	 * EntityEntry).
	 *
	 * @param entityName The name of the entity
	 * @param object The entity instance
	 * @param session The session
	 *
	 * @return The identifier
	 *
	 * @throws TransientObjectException if the entity is transient (does not yet have an identifier)
	 */
	public static CompletionStage<Serializable> getEntityIdentifierIfNotUnsaved(
			final String entityName,
			final Object object,
			final SessionImplementor session) throws TransientObjectException {
		if ( object == null ) {
			return RxUtil.nullFuture();
		}
		else {
			Serializable id = session.getContextEntityIdentifier( object );
			if ( id == null ) {
				// context-entity-identifier returns null explicitly if the entity
				// is not associated with the persistence context; so make some
				// deeper checks...
				return isTransient( entityName, object, Boolean.FALSE, session )
						.thenApply( trans -> {
							if ( trans ) {
								throw new TransientObjectException(
										"object references an unsaved transient instance - save the transient instance before flushing: " +
												(entityName == null ? session.guessEntityName( object ) : entityName)
								);
							}
							return session.getEntityPersister( entityName, object )
									.getIdentifier( object, session );
						} );
			}
			else {
				return RxUtil.completedFuture( id );
			}
		}
	}


	/**
	 * Disallow instantiation
	 */
	private ForeignKeys() {
	}

}