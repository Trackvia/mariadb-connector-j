package org.mariadb.jdbc.internal.queryresults;

/*
MariaDB Client for Java

Copyright (c) 2012-2014 Monty Program Ab.
Copyright (c) 2015-2017 MariaDB Ab.

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option)
any later version.

This library is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to Monty Program Ab info@montyprogram.com.

This particular MariaDB Client for Java file is work
derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
the following copyright and notice provisions:

Copyright (c) 2009-2011, Marcus Eriksson

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:
Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the driver nor the names of its contributors may not be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.
*/

import org.mariadb.jdbc.internal.protocol.Protocol;
import org.mariadb.jdbc.internal.queryresults.resultset.SelectResultSet;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Permit to store multiple update count / insert ids.
 * There is 3 different kind of information :
 * <ol>
 *     <li>standard : real update count and real insert ids</li>
 *     <li>Error : update count is set to EXECUTE_FAILED</li>
 *     <li>ResultSet : update count is set to -1</li>
 * </ol>
 *
 * -
 */
public class CmdInformationMultiple implements CmdInformation {

    private Queue<Long> insertIds;
    private Queue<Long> updateCounts;
    private int expectedSize;
    private int autoincrement;

    /**
     * Constructor, initialized with a standard result.
     *
     * @param insertId      generated insert id
     * @param updateCount   update count
     * @param expectedSize  expected batch size
     * @param autoincrement connection auto-increment
     */
    public CmdInformationMultiple(long insertId, long updateCount, int expectedSize, int autoincrement) {
        this.expectedSize = expectedSize;
        this.insertIds = new ConcurrentLinkedQueue<>();
        this.updateCounts = new ConcurrentLinkedQueue<>();
        if (insertId != 0) this.insertIds.add(insertId);
        this.updateCounts.add(updateCount);
        this.autoincrement = autoincrement;
    }

    /**
     * Constructor, initialized with a ResultSet result.
     *
     * @param updateCount   update count
     * @param expectedSize  expected batch size
     * @param autoincrement connection auto-increment
     */
    public CmdInformationMultiple(long updateCount, int expectedSize, int autoincrement) {
        this.expectedSize = expectedSize;
        this.insertIds = new ConcurrentLinkedQueue<>();
        this.updateCounts = new ConcurrentLinkedQueue<>();
        this.updateCounts.add(updateCount);
        this.autoincrement = autoincrement;
    }

    /**
     * Constructor, initialized with an error result.
     *
     * @param expectedSize  expected batch size.
     * @param autoincrement connection auto-increment
     */
    public CmdInformationMultiple(int expectedSize, int autoincrement) {
        this.expectedSize = expectedSize;
        this.insertIds = new ConcurrentLinkedQueue<>();
        this.updateCounts = new ConcurrentLinkedQueue<>();
        this.updateCounts.add((long) Statement.EXECUTE_FAILED);
        this.autoincrement = autoincrement;
    }

    @Override
    public void addStats(long updateCount) {
        this.updateCounts.add(updateCount);
    }

    @Override
    public void addStats(long updateCount, long insertId) {
        this.insertIds.add(insertId);
        this.updateCounts.add(updateCount);
    }

    @Override
    public int[] getUpdateCounts() {
        int[] ret = new int[Math.max(updateCounts.size(), expectedSize)];
        int pos = 0;
        for (Long updateCount : updateCounts) {
            ret[pos++] = updateCount.intValue();
        }

        //in case of Exception
        while (pos < ret.length) {
            ret[pos++] = Statement.EXECUTE_FAILED;
        }

        return ret;
    }


    @Override
    public long[] getLargeUpdateCounts() {
        long[] ret = new long[Math.max(updateCounts.size(), expectedSize)];
        int pos = 0;
        for (Long updateCount : updateCounts) {
            ret[pos++] = updateCount;
        }

        //in case of Exception
        while (pos < ret.length) {
            ret[pos++] = Statement.EXECUTE_FAILED;
        }

        return ret;
    }


    @Override
    public long getLargeUpdateCount() {
        Long updateCount = updateCounts.peek();
        return (updateCount == null) ? NO_UPDATE_COUNT : updateCount;
    }

    @Override
    public int getUpdateCount() {
        Long updateCount = updateCounts.peek();
        return (updateCount == null) ? NO_UPDATE_COUNT : updateCount.intValue();
    }

    /**
     * Return GeneratedKeys containing insert ids.
     *
     * @param protocol current protocol
     * @return a resultSet with insert ids.
     */
    public ResultSet getGeneratedKeys(Protocol protocol) {
        switch (updateCounts.size()) {
            case 0:
                return SelectResultSet.createEmptyResultSet();

            case 1:
                long[] ret = new long[updateCounts.peek().intValue()];
                for (int i = 0; i < updateCounts.peek(); i++) {
                    ret[i] = insertIds.peek() + i * autoincrement;
                }
                return SelectResultSet.createGeneratedData(ret, protocol, true);

            default:

                List<Long> generatedIds = new ArrayList<>();

                Iterator<Long> updateCountIterator = updateCounts.iterator();
                Iterator<Long> insertIdsIterator = insertIds.iterator();
                while (updateCountIterator.hasNext()) {
                    long updateCount = updateCountIterator.next();
                    if (updateCount > 0) { //and then > Statement.EXECUTE_FAILED && Statement.SUCCESS_NO_INFO
                        long insertId = insertIdsIterator.next();
                        if (insertId > 0) {
                            for (long i = 0; i < updateCount; i++) {
                                generatedIds.add(insertId + i * autoincrement);
                            }
                        }
                    }
                }

                long[] result = new long[generatedIds.size()];
                int counter = 0;
                for (Long l : generatedIds) result[counter++] = l;

                return SelectResultSet.createGeneratedData(result, protocol, true);

        }
    }

    public int getCurrentStatNumber() {
        return updateCounts.size();
    }

    @Override
    public boolean moreResults() {
        if (updateCounts.poll() != null) return isCurrentUpdateCount();
        return false;
    }

    @Override
    public boolean isCurrentUpdateCount() {
        Long updateCount = updateCounts.peek();
        return (updateCount == null) ? false : NO_UPDATE_COUNT != updateCount;
    }

}

