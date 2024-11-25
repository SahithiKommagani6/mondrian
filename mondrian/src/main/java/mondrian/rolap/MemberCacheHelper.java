/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2028-08-13
 ******************************************************************************/

package mondrian.rolap;

import mondrian.olap.Level;
import mondrian.olap.MondrianProperties;
import mondrian.olap.Util;
import mondrian.rolap.cache.*;
import mondrian.rolap.sql.MemberChildrenConstraint;
import mondrian.rolap.sql.TupleConstraint;
import mondrian.spi.DataSourceChangeListener;
import mondrian.util.*;

import java.util.*;
import java.util.Map.Entry;

import static org.apache.commons.collections.CollectionUtils.filter;

/**
 * Encapsulation of member caching.
 *
 * @author Will Gorman
 */
public class MemberCacheHelper implements MemberCache {

    private final SqlConstraintFactory sqlConstraintFactory =
        SqlConstraintFactory.instance();

    /** maps a parent member and constraint to a list of its children */
    final SmartMemberListCache<RolapMember, List<RolapMember>>
        mapMemberToChildren;

    /** maps a parent member to the collection of named children that have
     * been cached.  The collection can grow over time as new children are
     * loaded.
     */
    final SmartIncrementalCache<RolapMember, Collection<RolapMember>>
        mapParentToNamedChildren;

    /** a cache for all members to ensure uniqueness */
    SmartCache<Object, RolapMember> mapKeyToMember;
    RolapHierarchy rolapHierarchy;
    DataSourceChangeListener changeListener;

    /** maps a level to its members */
    final SmartMemberListCache<RolapLevel, List<RolapMember>>
        mapLevelToMembers;

    final MondrianProperties props;

    /**
     * Creates a MemberCacheHelper.
     *
     * @param rolapHierarchy Hierarchy
     */
    public MemberCacheHelper(RolapHierarchy rolapHierarchy) {
        this.rolapHierarchy = rolapHierarchy;
        this.mapLevelToMembers = new SmartMemberListCache<>();
        this.mapKeyToMember = new SoftSmartCache<>();
        this.mapMemberToChildren = new SmartMemberListCache<>();
        this.mapParentToNamedChildren = new SmartIncrementalCache<>();

        if (rolapHierarchy != null) {
            changeListener =
                rolapHierarchy.getRolapSchema().getDataSourceChangeListener();
        } else {
            changeListener = null;
        }

        props = MondrianProperties.instance();
    }

    public RolapMember getMember(
        Object key,
        boolean mustCheckCacheStatus)
    {
        if (mustCheckCacheStatus) {
            checkCacheStatus();
        }
        return mapKeyToMember.get(key);
    }

    // implement MemberCache
    public Object putMember(Object key, RolapMember value) {
        return mapKeyToMember.put(key, value);
    }

    // implement MemberCache
    public Object makeKey(RolapMember parent, Object key) {
        return new MemberKey(parent, key);
    }

    // implement MemberCache
    public RolapMember getMember(Object key) {
        return getMember(key, true);
    }

    public synchronized void checkCacheStatus() {
        if (changeListener != null && changeListener.isHierarchyChanged(rolapHierarchy)) {
            flushCache();
        }
    }

    public void putChildren(
        RolapLevel level,
        TupleConstraint constraint,
        List<RolapMember> members)
    {
        mapLevelToMembers.put(level, constraint, members);
    }

    public List<RolapMember> getChildrenFromCache(
        RolapMember member,
        MemberChildrenConstraint constraint)
    {
        if (constraint == null) {
            constraint =
                sqlConstraintFactory.getMemberChildrenConstraint(null);
        }
        if (constraint instanceof ChildByNameConstraint) {
            return findNamedChildrenInCache(
                member, ((ChildByNameConstraint) constraint).getChildNames());
        }
        return mapMemberToChildren.get(member, constraint);
    }

    /**
     * Attempts to find all children requested by the ChildByNameConstraint
     * in cache.  Returns null if the complete list is not found.
     */
    private List<RolapMember> findNamedChildrenInCache(
        final RolapMember parent, final List<String> childNames)
    {
        Collection<RolapMember> children =
            checkDefaultAndNamedChildrenCache(parent);
        if ((props.IgnoreInvalidMembers.get()
          || props.IgnoreInvalidMembersDuringQuery.get())
          && children != null
          && children.isEmpty())
        {
              // This can happen when we're telling mondrian to ignore
              // invalid members. We have an empty collection representing
              // the absence of a named child for a given parent. This does
              // not mean we missed loading children.
              return new ArrayList<>(children);
        }

        // Convert Collection to List for predicate filtering.
        final List<RolapMember> childrenList =
          children == null
            ? Collections.emptyList()
            : new ArrayList<>(children);

        // We have to check if we picked up all of the expected
        // members. If not return null.
        if (childNames == null
          || childrenList.isEmpty()
          || childNames.size() > childrenList.size())
        {
            return null;
        }

        // Keep only the requested members. We could have pulled
        // more than needed if we used a DefaultChildMemberNameConstraint
        // to populate a cache query with ChildMemberNameConstraint.
        filter( childrenList, member -> childNames.contains(
          ((RolapMember) member).getName()) );

        boolean foundAll = childrenList.size() == childNames.size();
        return !foundAll ? null : childrenList;
    }

    private Collection<RolapMember> checkDefaultAndNamedChildrenCache(
        RolapMember parent)
    {
        Collection<RolapMember> children = mapMemberToChildren
            .get(parent, DefaultMemberChildrenConstraint.instance());
        if (children == null) {
            children = mapParentToNamedChildren.get(parent);
        }
        return children;
    }


    public void putChildren(
        RolapMember member,
        MemberChildrenConstraint constraint,
        List<RolapMember> children)
    {
        if (constraint == null) {
            constraint =
                sqlConstraintFactory.getMemberChildrenConstraint(null);
        }
        if (constraint instanceof ChildByNameConstraint) {
            putChildrenInChildNameCache(member, children);
        } else {
            mapMemberToChildren.put(member, constraint, children);
        }
    }

    private void putChildrenInChildNameCache(
        final RolapMember parent,
        final List<RolapMember> children)
    {
        if (children == null ) {
            return;
        }

        if ((!props.IgnoreInvalidMembers.get()
          && !props.IgnoreInvalidMembersDuringQuery.get())
          && children.isEmpty())
        {
          // This is a special case. If we told mondrian to ignore
          // non-existing members, we still need to cache empty lists.
          // If this optimization does not kicks in, we'll keep loading
          // non existing children of null members.
          return;
        }

        Collection<RolapMember> cachedChildren =
            mapParentToNamedChildren.get(parent);
        if (cachedChildren == null) {
            // initialize with a sorted set
            mapParentToNamedChildren.put(
                parent, new TreeSet<>(children));
        } else {
            mapParentToNamedChildren.addToEntry(parent, children);
        }
    }

    public List<RolapMember> getLevelMembersFromCache(
        RolapLevel level,
        TupleConstraint constraint)
    {
        if (constraint == null) {
            constraint = sqlConstraintFactory.getLevelMembersConstraint(null);
        }
        return mapLevelToMembers.get(level, constraint);
    }

    // Must sync here because we want the three maps to be modified together.
    public synchronized void flushCache() {
        mapMemberToChildren.clear();
        mapKeyToMember.clear();
        mapLevelToMembers.clear();
        mapParentToNamedChildren.clear();
        // We also need to clear the approxRowCount of each level.
        for (Level level : rolapHierarchy.getLevels()) {
            ((RolapLevel)level).setApproxRowCount(Integer.MIN_VALUE);
        }
    }

    public DataSourceChangeListener getChangeListener() {
        return changeListener;
    }

    public void setChangeListener(DataSourceChangeListener listener) {
        changeListener = listener;
    }

    public boolean isMutable()
    {
        return true;
    }

    public synchronized RolapMember removeMember(Object key)
    {
        // Flush entries from the level-to-members map
        // for member's level and all child levels.
        // Important: Do this even if the member is apparently not in the cache.
        flushEntriesFromMapLevelToMembers( (MemberKey) key );

        final RolapMember member = getMember(key);
        if (member == null) {
            // not in cache
            return null;
        }

        // Drop member from the member-to-children map, wherever it occurs as
        // a parent or as a child, regardless of the constraint.
        final RolapMember parent = dropMembersFromMapMemberToChildren( member );

        mapParentToNamedChildren.getCache().execute(
          iterator -> {
              while (iterator.hasNext()) {
                  Entry<RolapMember, Collection<RolapMember>> entry =
                      iterator.next();
                  RolapMember currentMember = entry.getKey();
                  if (member.equals(currentMember)) {
                      iterator.remove();
                  } else if (parent.equals(currentMember)) {
                      entry.getValue().remove(member);
                  }
              }
          } );
            // drop it from the lookup-cache
            return mapKeyToMember.put(key, null);
        }

    private RolapMember dropMembersFromMapMemberToChildren( RolapMember member ) {
        final RolapMember parent = member.getParentMember();
        mapMemberToChildren.cache.execute(
          iter -> {
              while (iter.hasNext()) {
                  Entry<Pair
                      <RolapMember, Object>, List<RolapMember>> entry =
                          iter.next();
                  final RolapMember member1 = entry.getKey().left;
                  final Object constraint = entry.getKey().right;

                  // Cache key is (member's parent, constraint);
                  // cache value is a list of member's siblings;
                  // If constraint is trivial remove member from list
                  // of siblings; otherwise it's safer to nuke the cache
                  // entry
                  if (Util.equals(member1, parent)) {
                      if (constraint
                          == DefaultMemberChildrenConstraint.instance())
                      {
                          List<RolapMember> siblings = entry.getValue();
                          boolean removedIt = siblings.remove( member );
                          Util.discard(removedIt);
                      } else {
                          iter.remove();
                      }
                  }

                  // cache is (member, some constraint);
                  // cache value is list of member's children;
                  // remove cache entry
                  if (Util.equals(member1, member )) {
                      iter.remove();
                  }
              }
          } );
        return parent;
    }

    private void flushEntriesFromMapLevelToMembers( MemberKey key ) {
        RolapLevel level = key.getLevel();
        if (level == null) {
            level = (RolapLevel) this.rolapHierarchy.getLevels()[0];
        }
        final RolapLevel levelRef = level;
        mapLevelToMembers.getCache().execute(
          iterator -> {
              while (iterator.hasNext()) {
                  Entry<Pair
                      <RolapLevel, Object>, List<RolapMember>> entry =
                      iterator.next();
                  final RolapLevel cacheLevel = entry.getKey().left;
                  if (cacheLevel.equals(levelRef)
                      || (cacheLevel.getHierarchy()
                      .equals(levelRef.getHierarchy())
                      && cacheLevel.getDepth()
                      >= levelRef.getDepth()))
                  {
                      iterator.remove();
                  }
              }
          } );
    }

    public RolapMember removeMemberAndDescendants(Object key) {
        // Can use mapMemberToChildren recursively. No need to update inferior
        // lists of children. Do need to update inferior lists of level-peers.
        return null; // STUB
    }
}

// End MemberCacheHelper.java
